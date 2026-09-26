#ifndef GUEST_LINUX_SHELL_H
#define GUEST_LINUX_SHELL_H

#include <string>
#include <vector>
#include <map>
#include <memory>
#include <functional>
#include <chrono>

struct VfsNode {
    std::string name;
    bool isDirectory;
    std::string content;
    std::map<std::string, std::shared_ptr<VfsNode>> children;
    uint32_t mode; // permissions
    uint64_t size;
    std::chrono::system_clock::time_point mtime;

    VfsNode(const std::string& n, bool isDir, uint32_t perm = 0755)
        : name(n), isDirectory(isDir), mode(perm), size(0), mtime(std::chrono::system_clock::now()) {}
};

class GuestLinuxShell {
public:
    GuestLinuxShell(
        int cpuCores,
        uint64_t ramSizeBytes,
        const std::string& diskPath,
        uint64_t diskSizeBytes,
        const std::string& backendDesc
    );

    void setOutputCallback(std::function<void(const std::string&)> cb) {
        outputCallback = cb;
    }

    void handleCharInput(uint8_t ch);
    void executeCommandLine(const std::string& line);
    std::string getCurrentPrompt() const;
    void reset();

    // VFS operations
    bool createDirectory(const std::string& path);
    bool writeFile(const std::string& path, const std::string& data, bool append = false);
    bool readFile(const std::string& path, std::string& outData);
    bool deletePath(const std::string& path, bool recursive = false);
    std::shared_ptr<VfsNode> resolvePath(const std::string& path);

private:
    int cores;
    uint64_t ramBytes;
    std::string diskFile;
    uint64_t diskBytes;
    std::string backendDescription;

    std::string currentWorkingDir;
    std::map<std::string, std::string> environment;
    std::shared_ptr<VfsNode> rootVfs;

    std::string inputBuffer;
    std::vector<std::string> commandHistory;
    size_t historyIndex;
    std::chrono::steady_clock::time_point bootTime;

    std::function<void(const std::string&)> outputCallback;

    void initVfsHierarchy();
    std::string normalizePath(const std::string& path);
    void writeOutput(const std::string& text);
    void writeError(const std::string& text);

    // Command dispatchers
    void runCommand(const std::string& cmdLine);
    void cmdLs(const std::vector<std::string>& args);
    void cmdCd(const std::vector<std::string>& args);
    void cmdPwd();
    void cmdCat(const std::vector<std::string>& args);
    void cmdEcho(const std::vector<std::string>& args);
    void cmdTouch(const std::vector<std::string>& args);
    void cmdMkdir(const std::vector<std::string>& args);
    void cmdRm(const std::vector<std::string>& args);
    void cmdUname(const std::vector<std::string>& args);
    void cmdWhoami();
    void cmdId();
    void cmdDate();
    void cmdUptime();
    void cmdDf(const std::vector<std::string>& args);
    void cmdFree(const std::vector<std::string>& args);
    void cmdPs(const std::vector<std::string>& args);
    void cmdDmesg();
    void cmdGrep(const std::vector<std::string>& args);
    void cmdHead(const std::vector<std::string>& args);
    void cmdTail(const std::vector<std::string>& args);
    void cmdWc(const std::vector<std::string>& args);
    void cmdEnv();
    void cmdExport(const std::vector<std::string>& args);
    void cmdStat(const std::vector<std::string>& args);
    void cmdHelp();
};

#endif // GUEST_LINUX_SHELL_H
