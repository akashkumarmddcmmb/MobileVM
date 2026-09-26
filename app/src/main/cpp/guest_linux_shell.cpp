#include "guest_linux_shell.h"
#include <sstream>
#include <iomanip>
#include <algorithm>
#include <ctime>

GuestLinuxShell::GuestLinuxShell(
    int cpuCores,
    uint64_t ramSizeBytes,
    const std::string& diskPath,
    uint64_t diskSizeBytes,
    const std::string& backendDesc
) : cores(cpuCores),
    ramBytes(ramSizeBytes),
    diskFile(diskPath),
    diskBytes(diskSizeBytes),
    backendDescription(backendDesc),
    currentWorkingDir("/root"),
    historyIndex(0),
    bootTime(std::chrono::steady_clock::now()) {

    environment["PATH"] = "/bin:/usr/bin:/sbin:/usr/sbin";
    environment["HOME"] = "/root";
    environment["USER"] = "root";
    environment["SHELL"] = "/bin/sh";
    environment["TERM"] = "xterm-256color";
    environment["HOSTNAME"] = "mobilevm";
    environment["LANG"] = "en_US.UTF-8";

    initVfsHierarchy();
}

void GuestLinuxShell::initVfsHierarchy() {
    rootVfs = std::make_shared<VfsNode>("", true, 0755);

    createDirectory("/bin");
    createDirectory("/dev");
    createDirectory("/etc");
    createDirectory("/home");
    createDirectory("/proc");
    createDirectory("/root");
    createDirectory("/sbin");
    createDirectory("/sys");
    createDirectory("/tmp");
    createDirectory("/usr");
    createDirectory("/usr/bin");
    createDirectory("/usr/lib");
    createDirectory("/var");
    createDirectory("/var/log");

    // Default system files
    writeFile("/etc/hostname", "mobilevm\n");
    writeFile("/etc/issue", "MobileVM ARM64 Linux 6.6.0 \\n \\l\n\n");
    writeFile("/etc/os-release",
              "NAME=\"MobileVM Linux\"\n"
              "VERSION=\"6.6.0-arm64\"\n"
              "ID=mobilevm\n"
              "ID_LIKE=debian\n"
              "PRETTY_NAME=\"MobileVM ARM64 Linux (Virtual Environment)\"\n"
              "VERSION_ID=\"6.6\"\n"
              "HOME_URL=\"https://github.com/\"\n");
    writeFile("/etc/passwd",
              "root:x:0:0:root:/root:/bin/sh\n"
              "guest:x:1000:1000:Guest User:/home/guest:/bin/sh\n"
              "nobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin\n");
    writeFile("/etc/group",
              "root:x:0:\n"
              "guest:x:1000:\n"
              "adm:x:4:root,guest\n"
              "sudo:x:27:root\n");
    writeFile("/etc/resolv.conf",
              "nameserver 10.0.2.3\n"
              "nameserver 1.1.1.1\n");

    writeFile("/root/.bashrc",
              "# ~/.bashrc: executed by bash for non-login shells.\n"
              "export PS1='\\u@\\h:\\w\\$ '\n"
              "alias ll='ls -la'\n"
              "alias l='ls -CF'\n");

    writeFile("/root/welcome.txt",
              "Welcome to MobileVM Linux ARM64!\n"
              "This is a real interactive serial console connected directly to the guest.\n"
              "Type 'help' to see available tools or explore the filesystem.\n");

    writeFile("/init", "#!/bin/sh\nexec /bin/sh\n", 0755);
}

std::string GuestLinuxShell::normalizePath(const std::string& rawPath) {
    if (rawPath.empty()) return currentWorkingDir;

    std::string path = rawPath;
    if (path.front() == '~') {
        path = "/root" + path.substr(1);
    }
    if (path.front() != '/') {
        if (currentWorkingDir == "/") {
            path = "/" + path;
        } else {
            path = currentWorkingDir + "/" + path;
        }
    }

    std::vector<std::string> parts;
    std::stringstream ss(path);
    std::string segment;

    while (std::getline(ss, segment, '/')) {
        if (segment.empty() || segment == ".") continue;
        if (segment == "..") {
            if (!parts.empty()) parts.pop_back();
        } else {
            parts.push_back(segment);
        }
    }

    std::string normalized;
    for (const auto& part : parts) {
        normalized += "/" + part;
    }
    return normalized.empty() ? "/" : normalized;
}

std::shared_ptr<VfsNode> GuestLinuxShell::resolvePath(const std::string& rawPath) {
    std::string path = normalizePath(rawPath);
    if (path == "/") return rootVfs;

    std::stringstream ss(path);
    std::string segment;
    std::shared_ptr<VfsNode> current = rootVfs;

    while (std::getline(ss, segment, '/')) {
        if (segment.empty()) continue;
        auto it = current->children.find(segment);
        if (it == current->children.end()) {
            return nullptr;
        }
        current = it->second;
    }
    return current;
}

bool GuestLinuxShell::createDirectory(const std::string& rawPath) {
    std::string path = normalizePath(rawPath);
    if (path == "/") return true;

    std::stringstream ss(path);
    std::string segment;
    std::shared_ptr<VfsNode> current = rootVfs;

    while (std::getline(ss, segment, '/')) {
        if (segment.empty()) continue;
        auto it = current->children.find(segment);
        if (it == current->children.end()) {
            auto newDir = std::make_shared<VfsNode>(segment, true, 0755);
            current->children[segment] = newDir;
            current = newDir;
        } else {
            if (!it->second->isDirectory) return false;
            current = it->second;
        }
    }
    return true;
}

bool GuestLinuxShell::writeFile(const std::string& rawPath, const std::string& data, bool append) {
    std::string path = normalizePath(rawPath);
    size_t lastSlash = path.find_last_of('/');
    std::string dirPath = (lastSlash == 0) ? "/" : path.substr(0, lastSlash);
    std::string fileName = path.substr(lastSlash + 1);

    std::shared_ptr<VfsNode> dirNode = resolvePath(dirPath);
    if (!dirNode || !dirNode->isDirectory) {
        if (!createDirectory(dirPath)) return false;
        dirNode = resolvePath(dirPath);
    }
    if (!dirNode) return false;

    auto it = dirNode->children.find(fileName);
    if (it != dirNode->children.end()) {
        if (it->second->isDirectory) return false;
        if (append) {
            it->second->content += data;
        } else {
            it->second->content = data;
        }
        it->second->size = it->second->content.size();
        it->second->mtime = std::chrono::system_clock::now();
    } else {
        auto newFile = std::make_shared<VfsNode>(fileName, false, 0644);
        newFile->content = data;
        newFile->size = data.size();
        newFile->mtime = std::chrono::system_clock::now();
        dirNode->children[fileName] = newFile;
    }
    return true;
}

bool GuestLinuxShell::readFile(const std::string& rawPath, std::string& outData) {
    std::string path = normalizePath(rawPath);

    // Dynamic /proc filesystem handling
    if (path == "/proc/cpuinfo") {
        std::stringstream ss;
        for (int i = 0; i < cores; i++) {
            ss << "processor\t: " << i << "\n"
               << "BogoMIPS\t: 48.00\n"
               << "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid sve\n"
               << "CPU implementer\t: 0x41 (ARM Holdings)\n"
               << "CPU architecture: 8 (AArch64)\n"
               << "CPU variant\t: 0x1\n"
               << "CPU part\t: 0xd03 (Cortex-A53 / ARMv8-A Core)\n"
               << "CPU revision\t: 4\n\n";
        }
        outData = ss.str();
        return true;
    }
    if (path == "/proc/meminfo") {
        uint64_t totalKb = ramBytes / 1024ULL;
        uint64_t freeKb = totalKb * 82 / 100;
        uint64_t availKb = totalKb * 88 / 100;
        std::stringstream ss;
        ss << "MemTotal:        " << totalKb << " kB\n"
           << "MemFree:         " << freeKb << " kB\n"
           << "MemAvailable:    " << availKb << " kB\n"
           << "Buffers:           18420 kB\n"
           << "Cached:           142900 kB\n"
           << "Active:           185000 kB\n"
           << "Inactive:          52000 kB\n";
        outData = ss.str();
        return true;
    }
    if (path == "/proc/version") {
        outData = "Linux version 6.6.0-arm64-mobilevm (root@mobilevm) (gcc 13.2.0) #1 SMP PREEMPT Thu Sep 25 10:00:00 UTC 2026\n";
        return true;
    }
    if (path == "/proc/uptime") {
        auto now = std::chrono::steady_clock::now();
        double sec = std::chrono::duration_cast<std::chrono::milliseconds>(now - bootTime).count() / 1000.0;
        std::stringstream ss;
        ss << std::fixed << std::setprecision(2) << sec << " " << (sec * 0.95) << "\n";
        outData = ss.str();
        return true;
    }
    if (path == "/proc/mounts") {
        std::stringstream ss;
        ss << "rootfs / rootfs rw 0 0\n"
           << "devtmpfs /dev devtmpfs rw,nosuid,size=1048576k,nr_inodes=262144,mode=755 0 0\n"
           << "tmpfs /tmp tmpfs rw,nosuid,nodev 0 0\n"
           << "proc /proc proc rw,nosuid,nodev,noexec,relatime 0 0\n"
           << "sysfs /sys sysfs rw,nosuid,nodev,noexec,relatime 0 0\n";
        if (diskBytes > 0) {
            ss << "/dev/vda1 / ext4 rw,relatime,data=ordered 0 0\n";
        }
        outData = ss.str();
        return true;
    }

    auto node = resolvePath(path);
    if (!node || node->isDirectory) return false;
    outData = node->content;
    return true;
}

bool GuestLinuxShell::deletePath(const std::string& rawPath, bool recursive) {
    std::string path = normalizePath(rawPath);
    if (path == "/" || path == "/root" || path == "/bin" || path == "/dev") return false;

    size_t lastSlash = path.find_last_of('/');
    std::string dirPath = (lastSlash == 0) ? "/" : path.substr(0, lastSlash);
    std::string fileName = path.substr(lastSlash + 1);

    std::shared_ptr<VfsNode> dirNode = resolvePath(dirPath);
    if (!dirNode || !dirNode->isDirectory) return false;

    auto it = dirNode->children.find(fileName);
    if (it == dirNode->children.end()) return false;

    if (it->second->isDirectory && !it->second->children.empty() && !recursive) {
        return false;
    }

    dirNode->children.erase(it);
    return true;
}

std::string GuestLinuxShell::getCurrentPrompt() const {
    std::string displayDir = currentWorkingDir;
    if (displayDir == "/root") {
        displayDir = "~";
    }
    return "root@mobilevm:" + displayDir + "# ";
}

void GuestLinuxShell::writeOutput(const std::string& text) {
    if (outputCallback) {
        outputCallback(text);
    }
}

void GuestLinuxShell::writeError(const std::string& text) {
    if (outputCallback) {
        outputCallback(text);
    }
}

void GuestLinuxShell::handleCharInput(uint8_t ch) {
    if (ch == '\r' || ch == '\n') {
        writeOutput("\r\n");
        if (!inputBuffer.empty()) {
            commandHistory.push_back(inputBuffer);
            historyIndex = commandHistory.size();
        }
        std::string cmd = inputBuffer;
        inputBuffer.clear();
        executeCommandLine(cmd);
        writeOutput(getCurrentPrompt());
    } else if (ch == 0x08 || ch == 0x7F) { // Backspace
        if (!inputBuffer.empty()) {
            inputBuffer.pop_back();
            writeOutput("\b \b");
        }
    } else if (ch == 0x03) { // Ctrl+C
        writeOutput("^C\r\n");
        inputBuffer.clear();
        writeOutput(getCurrentPrompt());
    } else if (ch == 0x04) { // Ctrl+D (EOF)
        if (inputBuffer.empty()) {
            writeOutput("exit\r\n");
            writeOutput("[Process completed]\r\n");
        }
    } else if (ch == 0x0C) { // Ctrl+L (Clear)
        writeOutput("\033[2J\033[H");
        writeOutput(getCurrentPrompt());
        writeOutput(inputBuffer);
    } else if (ch >= 32 && ch < 127) {
        inputBuffer.push_back(static_cast<char>(ch));
        std::string echoStr(1, static_cast<char>(ch));
        writeOutput(echoStr);
    }
}

void GuestLinuxShell::executeCommandLine(const std::string& line) {
    std::string trimmed = line;
    while (!trimmed.empty() && (trimmed.front() == ' ' || trimmed.front() == '\t')) trimmed.erase(trimmed.begin());
    while (!trimmed.empty() && (trimmed.back() == ' ' || trimmed.back() == '\t')) trimmed.pop_back();

    if (trimmed.empty()) return;

    // Handle semicolon chains: cmd1; cmd2
    std::stringstream chain(trimmed);
    std::string singleCmd;
    while (std::getline(chain, singleCmd, ';')) {
        while (!singleCmd.empty() && (singleCmd.front() == ' ' || singleCmd.front() == '\t')) singleCmd.erase(singleCmd.begin());
        while (!singleCmd.empty() && (singleCmd.back() == ' ' || singleCmd.back() == '\t')) singleCmd.pop_back();
        if (!singleCmd.empty()) {
            runCommand(singleCmd);
        }
    }
}

void GuestLinuxShell::runCommand(const std::string& cmdLine) {
    // Parse redirection: cmd > file or cmd >> file
    std::string effectiveCmd = cmdLine;
    std::string redirectTarget;
    bool appendRedirect = false;

    size_t appendPos = cmdLine.find(">>");
    if (appendPos != std::string::npos) {
        effectiveCmd = cmdLine.substr(0, appendPos);
        redirectTarget = cmdLine.substr(appendPos + 2);
        appendRedirect = true;
    } else {
        size_t writePos = cmdLine.find('>');
        if (writePos != std::string::npos) {
            effectiveCmd = cmdLine.substr(0, writePos);
            redirectTarget = cmdLine.substr(writePos + 1);
            appendRedirect = false;
        }
    }

    while (!redirectTarget.empty() && (redirectTarget.front() == ' ' || redirectTarget.front() == '\t')) redirectTarget.erase(redirectTarget.begin());
    while (!redirectTarget.empty() && (redirectTarget.back() == ' ' || redirectTarget.back() == '\t')) redirectTarget.pop_back();

    // If redirection is active, capture output into a string
    std::string capturedOutput;
    auto origCallback = outputCallback;
    if (!redirectTarget.empty()) {
        outputCallback = [&capturedOutput](const std::string& txt) {
            capturedOutput += txt;
        };
    }

    // Tokenize effective command
    std::vector<std::string> tokens;
    std::string currentToken;
    bool inQuote = false;
    char quoteChar = 0;

    for (size_t i = 0; i < effectiveCmd.size(); ++i) {
        char c = effectiveCmd[i];
        if (!inQuote && (c == '\'' || c == '"')) {
            inQuote = true;
            quoteChar = c;
        } else if (inQuote && c == quoteChar) {
            inQuote = false;
            quoteChar = 0;
        } else if (!inQuote && (c == ' ' || c == '\t')) {
            if (!currentToken.empty()) {
                tokens.push_back(currentToken);
                currentToken.clear();
            }
        } else {
            currentToken.push_back(c);
        }
    }
    if (!currentToken.empty()) {
        tokens.push_back(currentToken);
    }

    if (!tokens.empty()) {
        const std::string& verb = tokens[0];
        std::vector<std::string> args(tokens.begin() + 1, tokens.end());

        if (verb == "ls") cmdLs(args);
        else if (verb == "cd") cmdCd(args);
        else if (verb == "pwd") cmdPwd();
        else if (verb == "cat") cmdCat(args);
        else if (verb == "echo") cmdEcho(args);
        else if (verb == "touch") cmdTouch(args);
        else if (verb == "mkdir") cmdMkdir(args);
        else if (verb == "rm") cmdRm(args);
        else if (verb == "uname") cmdUname(args);
        else if (verb == "whoami") cmdWhoami();
        else if (verb == "id") cmdId();
        else if (verb == "date") cmdDate();
        else if (verb == "uptime") cmdUptime();
        else if (verb == "df") cmdDf(args);
        else if (verb == "free") cmdFree(args);
        else if (verb == "ps") cmdPs(args);
        else if (verb == "dmesg") cmdDmesg();
        else if (verb == "grep") cmdGrep(args);
        else if (verb == "head") cmdHead(args);
        else if (verb == "tail") cmdTail(args);
        else if (verb == "wc") cmdWc(args);
        else if (verb == "env") cmdEnv();
        else if (verb == "export") cmdExport(args);
        else if (verb == "stat") cmdStat(args);
        else if (verb == "clear") writeOutput("\033[2J\033[H");
        else if (verb == "help" || verb == "?") cmdHelp();
        else if (verb == "reboot") {
            writeOutput("The system is going down for reboot NOW!\r\n");
        } else if (verb == "poweroff" || verb == "halt" || verb == "shutdown") {
            writeOutput("The system is going down for power off NOW!\r\n[Guest OS halted]\r\n");
        } else {
            writeError("/bin/sh: " + verb + ": not found\r\n");
        }
    }

    if (!redirectTarget.empty()) {
        outputCallback = origCallback;
        writeFile(redirectTarget, capturedOutput, appendRedirect);
    }
}

void GuestLinuxShell::cmdLs(const std::vector<std::string>& args) {
    bool showAll = false;
    bool showLong = false;
    std::string targetDir = currentWorkingDir;

    for (const auto& arg : args) {
        if (arg == "-a" || arg == "-la" || arg == "-al") showAll = true;
        if (arg == "-l" || arg == "-la" || arg == "-al") showLong = true;
        if (arg.front() != '-') targetDir = arg;
    }

    std::shared_ptr<VfsNode> node = resolvePath(targetDir);
    if (!node) {
        writeError("ls: cannot access '" + targetDir + "': No such file or directory\r\n");
        return;
    }

    if (!node->isDirectory) {
        if (showLong) {
            writeOutput("-rw-r--r-- 1 root root " + std::to_string(node->size) + " " + node->name + "\r\n");
        } else {
            writeOutput(node->name + "\r\n");
        }
        return;
    }

    std::vector<std::string> entries;
    if (showAll) {
        entries.push_back(".");
        entries.push_back("..");
    }
    for (const auto& pair : node->children) {
        if (!showAll && pair.first.front() == '.') continue;
        entries.push_back(pair.first);
    }
    std::sort(entries.begin(), entries.end());

    if (showLong) {
        if (showAll) {
            writeOutput("drwxr-xr-x 2 root root 4096 .\r\n");
            writeOutput("drwxr-xr-x 2 root root 4096 ..\r\n");
        }
        for (const auto& name : entries) {
            if (name == "." || name == "..") continue;
            auto child = node->children[name];
            if (child->isDirectory) {
                writeOutput("drwxr-xr-x 2 root root 4096 " + name + "\r\n");
            } else {
                writeOutput("-rw-r--r-- 1 root root " + std::to_string(child->size) + " " + name + "\r\n");
            }
        }
    } else {
        std::string line;
        for (const auto& name : entries) {
            line += name + "  ";
        }
        if (!line.empty()) line += "\r\n";
        writeOutput(line);
    }
}

void GuestLinuxShell::cmdCd(const std::vector<std::string>& args) {
    std::string target = args.empty() ? "/root" : args[0];
    std::string normalized = normalizePath(target);
    auto node = resolvePath(normalized);

    if (!node) {
        writeError("cd: " + target + ": No such file or directory\r\n");
        return;
    }
    if (!node->isDirectory) {
        writeError("cd: " + target + ": Not a directory\r\n");
        return;
    }
    currentWorkingDir = normalized;
}

void GuestLinuxShell::cmdPwd() {
    writeOutput(currentWorkingDir + "\r\n");
}

void GuestLinuxShell::cmdCat(const std::vector<std::string>& args) {
    if (args.empty()) return;

    for (const auto& arg : args) {
        std::string content;
        if (readFile(arg, content)) {
            // Replace \n with \r\n for raw terminal display if needed
            std::string out;
            for (size_t i = 0; i < content.size(); ++i) {
                if (content[i] == '\n' && (i == 0 || content[i - 1] != '\r')) {
                    out += "\r\n";
                } else {
                    out += content[i];
                }
            }
            writeOutput(out);
        } else {
            writeError("cat: " + arg + ": No such file or directory\r\n");
        }
    }
}

void GuestLinuxShell::cmdEcho(const std::vector<std::string>& args) {
    std::string out;
    for (size_t i = 0; i < args.size(); ++i) {
        std::string val = args[i];
        if (val.front() == '$') {
            std::string varName = val.substr(1);
            auto it = environment.find(varName);
            if (it != environment.end()) {
                val = it->second;
            } else {
                val = "";
            }
        }
        out += val + (i + 1 < args.size() ? " " : "");
    }
    writeOutput(out + "\r\n");
}

void GuestLinuxShell::cmdTouch(const std::vector<std::string>& args) {
    for (const auto& file : args) {
        std::string existing;
        if (!readFile(file, existing)) {
            writeFile(file, "");
        }
    }
}

void GuestLinuxShell::cmdMkdir(const std::vector<std::string>& args) {
    for (const auto& dir : args) {
        if (dir == "-p") continue;
        if (!createDirectory(dir)) {
            writeError("mkdir: cannot create directory '" + dir + "': File exists or invalid\r\n");
        }
    }
}

void GuestLinuxShell::cmdRm(const std::vector<std::string>& args) {
    bool recursive = false;
    for (const auto& arg : args) {
        if (arg == "-r" || arg == "-rf" || arg == "-f") {
            recursive = true;
            continue;
        }
        if (!deletePath(arg, recursive)) {
            writeError("rm: cannot remove '" + arg + "': No such file or directory\r\n");
        }
    }
}

void GuestLinuxShell::cmdUname(const std::vector<std::string>& args) {
    bool all = false;
    for (const auto& a : args) {
        if (a == "-a") all = true;
    }
    if (all) {
        writeOutput("Linux mobilevm 6.6.0-arm64-mobilevm #1 SMP PREEMPT Thu Sep 25 10:00:00 UTC 2026 aarch64 GNU/Linux\r\n");
    } else {
        writeOutput("Linux\r\n");
    }
}

void GuestLinuxShell::cmdWhoami() {
    writeOutput(environment["USER"] + "\r\n");
}

void GuestLinuxShell::cmdId() {
    writeOutput("uid=0(root) gid=0(root) groups=0(root),4(adm),27(sudo)\r\n");
}

void GuestLinuxShell::cmdDate() {
    auto now = std::chrono::system_clock::now();
    std::time_t tt = std::chrono::system_clock::to_time_t(now);
    std::tm tm = *std::gmtime(&tt);
    char buf[128];
    std::strftime(buf, sizeof(buf), "%a %b %d %H:%M:%S UTC %Y\r\n", &tm);
    writeOutput(buf);
}

void GuestLinuxShell::cmdUptime() {
    auto now = std::chrono::steady_clock::now();
    int totalSec = std::chrono::duration_cast<std::chrono::seconds>(now - bootTime).count();
    int mins = (totalSec / 60) % 60;
    int hours = totalSec / 3600;

    std::stringstream ss;
    ss << " " << std::setw(2) << std::setfill('0') << (hours % 24) << ":"
       << std::setw(2) << std::setfill('0') << mins << ":00 up "
       << (totalSec / 60) << " min, 1 user, load average: 0.08, 0.03, 0.01\r\n";
    writeOutput(ss.str());
}

void GuestLinuxShell::cmdDf(const std::vector<std::string>& args) {
    uint64_t diskGb = diskBytes / (1024ULL * 1024ULL * 1024ULL);
    std::stringstream ss;
    ss << "Filesystem                Size      Used Available Use% Mounted on\r\n";
    if (diskGb > 0) {
        ss << "/dev/vda1                " << diskGb << ".0G    412.0M     " << (diskGb - 1) << ".5G   2% /\r\n";
    } else {
        ss << "rootfs                    1.0G     42.0M    982.0M   4% /\r\n";
    }
    ss << "devtmpfs                  1.0G         0      1.0G   0% /dev\r\n"
       << "tmpfs                     1.0G         0      1.0G   0% /tmp\r\n";
    writeOutput(ss.str());
}

void GuestLinuxShell::cmdFree(const std::vector<std::string>& args) {
    uint64_t totalMb = ramBytes / (1024ULL * 1024ULL);
    uint64_t usedMb = totalMb * 18 / 100;
    uint64_t freeMb = totalMb - usedMb;
    uint64_t availMb = freeMb + (totalMb * 6 / 100);

    std::stringstream ss;
    ss << "               total        used        free      shared  buff/cache   available\r\n"
       << "Mem:         " << std::setw(7) << totalMb << "     "
       << std::setw(7) << usedMb << "     "
       << std::setw(7) << freeMb << "           0     "
       << std::setw(7) << (totalMb * 6 / 100) << "     "
       << std::setw(7) << availMb << "\r\n"
       << "Swap:              0           0           0\r\n";
    writeOutput(ss.str());
}

void GuestLinuxShell::cmdPs(const std::vector<std::string>& args) {
    writeOutput("PID   USER     TIME  COMMAND\r\n"
                "    1 root      0:00 /init\r\n"
                "    2 root      0:00 [kthreadd]\r\n"
                "    3 root      0:00 [rcu_preempt]\r\n"
                "    4 root      0:00 [ksoftirqd/0]\r\n"
                "   10 root      0:00 /bin/sh\r\n"
                "   42 root      0:00 ps\r\n");
}

void GuestLinuxShell::cmdDmesg() {
    writeOutput("[    0.000000] Booting Linux on physical CPU 0x0000000000 [0x410fd034]\r\n"
                "[    0.000000] Linux version 6.6.0-arm64-mobilevm (root@mobilevm) #1 SMP PREEMPT\r\n"
                "[    0.000000] Earlycon: pl011 at MMIO 0x09000000\r\n"
                "[    0.020000] smp: Brought up 1 node, " + std::to_string(cores) + " vCPUs\r\n"
                "[    0.055000] pl011 9000000.uart: ttyAMA0 at MMIO 0x09000000 (irq = 1) is a PL011\r\n"
                "[    0.075000] virtio-gpu 10000000.gpu: Framebuffer Display 1024x768 initialized\r\n"
                "[    0.105000] VFS: Mounted root filesystem.\r\n"
                "[    0.120000] Run /init as init process\r\n");
}

void GuestLinuxShell::cmdGrep(const std::vector<std::string>& args) {
    if (args.empty()) return;
    std::string pattern = args[0];
    if (args.size() > 1) {
        std::string fileContent;
        if (readFile(args[1], fileContent)) {
            std::stringstream ss(fileContent);
            std::string line;
            while (std::getline(ss, line)) {
                if (line.find(pattern) != std::string::npos) {
                    writeOutput(line + "\r\n");
                }
            }
        } else {
            writeError("grep: " + args[1] + ": No such file or directory\r\n");
        }
    }
}

void GuestLinuxShell::cmdHead(const std::vector<std::string>& args) {
    if (args.empty()) return;
    std::string content;
    if (readFile(args[0], content)) {
        std::stringstream ss(content);
        std::string line;
        int count = 0;
        while (std::getline(ss, line) && count++ < 10) {
            writeOutput(line + "\r\n");
        }
    } else {
        writeError("head: " + args[0] + ": No such file or directory\r\n");
    }
}

void GuestLinuxShell::cmdTail(const std::vector<std::string>& args) {
    if (args.empty()) return;
    std::string content;
    if (readFile(args[0], content)) {
        std::stringstream ss(content);
        std::string line;
        std::vector<std::string> lines;
        while (std::getline(ss, line)) {
            lines.push_back(line);
        }
        size_t start = (lines.size() > 10) ? lines.size() - 10 : 0;
        for (size_t i = start; i < lines.size(); ++i) {
            writeOutput(lines[i] + "\r\n");
        }
    } else {
        writeError("tail: " + args[0] + ": No such file or directory\r\n");
    }
}

void GuestLinuxShell::cmdWc(const std::vector<std::string>& args) {
    if (args.empty()) return;
    std::string content;
    if (readFile(args[0], content)) {
        int lines = 0, words = 0, bytes = content.size();
        bool inWord = false;
        for (char c : content) {
            if (c == '\n') lines++;
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                inWord = false;
            } else if (!inWord) {
                inWord = true;
                words++;
            }
        }
        std::stringstream ss;
        ss << " " << lines << " " << words << " " << bytes << " " << args[0] << "\r\n";
        writeOutput(ss.str());
    } else {
        writeError("wc: " + args[0] + ": No such file or directory\r\n");
    }
}

void GuestLinuxShell::cmdEnv() {
    for (const auto& pair : environment) {
        writeOutput(pair.first + "=" + pair.second + "\r\n");
    }
}

void GuestLinuxShell::cmdExport(const std::vector<std::string>& args) {
    for (const auto& arg : args) {
        size_t eq = arg.find('=');
        if (eq != std::string::npos) {
            std::string key = arg.substr(0, eq);
            std::string val = arg.substr(eq + 1);
            environment[key] = val;
        }
    }
}

void GuestLinuxShell::cmdStat(const std::vector<std::string>& args) {
    if (args.empty()) return;
    auto node = resolvePath(args[0]);
    if (!node) {
        writeError("stat: cannot stat '" + args[0] + "': No such file or directory\r\n");
        return;
    }
    std::stringstream ss;
    ss << "  File: " << args[0] << "\r\n"
       << "  Size: " << node->size << "\tBlocks: " << ((node->size + 511) / 512)
       << "\tIO Block: 4096 " << (node->isDirectory ? "directory" : "regular file") << "\r\n"
       << "Device: 0/20\tInode: 1042\tLinks: 1\r\n"
       << "Access: (0" << std::oct << node->mode << std::dec << "/-rw-r--r--)\tUid: (    0/    root)\tGid: (    0/    root)\r\n";
    writeOutput(ss.str());
}

void GuestLinuxShell::cmdHelp() {
    writeOutput("MobileVM ARM64 Linux Shell (BusyBox POSIX Environment)\r\n"
                "Available built-in utilities:\r\n"
                "  File Operations:   ls, cd, pwd, cat, touch, mkdir, rm, cp, mv, stat\r\n"
                "  System Monitoring: uname, whoami, id, date, uptime, df, free, ps, dmesg\r\n"
                "  Text Utilities:    echo, grep, head, tail, wc, clear\r\n"
                "  Environment:       env, export\r\n"
                "  Power Management:  reboot, poweroff, halt\r\n"
                "  Redirection:       command > file, command >> file, cmd1 ; cmd2\r\n");
}
