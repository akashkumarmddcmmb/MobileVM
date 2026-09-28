package com.example.vm.security

/**
 * MobileVM Legal Compliance, License Registry, and Copyright Protection System.
 *
 * Ensures:
 * 1. Absolute compliance with all third-party open source licenses (GPLv2, MIT, Apache 2.0, Canonical IP).
 * 2. Strict copyright protection for the MobileVM project to prevent unauthorized cloning, copying, or redistribution.
 * 3. Clear attribution and disclaimers to prevent copyright strikes or legal disputes.
 */
data class SoftwareLicense(
    val id: String,
    val name: String,
    val owner: String,
    val licenseType: String,
    val isThirdParty: Boolean,
    val summary: String,
    val fullText: String,
    val officialSourceUrl: String
)

object LegalLicenseManager {

    /**
     * MobileVM Proprietary Copyright & Anti-Copy Protection Notice.
     * Protects the author's code, emulator architecture, UI, and algorithms.
     */
    val PROPRIETARY_COPYRIGHT_NOTICE = """
        ========================================================================
        MOBILEVM™ HYPERVISOR & ARCHITECTURE SUITE
        COPYRIGHT © 2026 AKASH KUMAR / MOBILEVM AUTHORS. ALL RIGHTS RESERVED.
        ========================================================================

        PROPRIETARY AND CONFIDENTIAL:
        This software application, including all source code, virtual hardware
        emulation logic (ARM64 CPU interpreter, PL011 UART, DTB generators),
        user interface designs, graphics, and architectural implementations, is
        the exclusive intellectual property of Akash Kumar / MobileVM Authors.

        ANTI-PIRACY & COPYRIGHT ENFORCEMENT:
        1. NO UNAUTHORIZED COPYING: No part of this software, its binary assets,
           or internal structures may be copied, reproduced, repackaged, or
           cloned under any circumstance without prior written consent.
        2. NO REVERSE ENGINEERING: Decompilation, disassembly, reverse engineering,
           tampering, or circumvention of binary integrity protection is strictly
           prohibited and violates international copyright laws.
        3. NO UNAUTHORIZED COMMERCIAL REDISTRIBUTION: Selling, sublicensing, or
           bundling this software or derivatives thereof is prohibited.
        4. HARDWARE/SIGNATURE BINDING: The application incorporates active
           signature and package verification to detect unauthorized re-signed
           or modded distributions.

        VIOLATORS WILL BE PROSECUTED UNDER DOMESTIC AND INTERNATIONAL COPYRIGHT
        AND DIGITAL MILLENNIUM COPYRIGHT ACT (DMCA) STATUTES.
    """.trimIndent()

    /**
     * Complete registry of all licenses for services, guest operating systems,
     * and libraries used by MobileVM.
     */
    val LICENSES: List<SoftwareLicense> = listOf(
        SoftwareLicense(
            id = "mobilevm_proprietary",
            name = "MobileVM Proprietary Application License",
            owner = "Akash Kumar / MobileVM Authors",
            licenseType = "Proprietary / All Rights Reserved",
            isThirdParty = false,
            summary = "Guarantees exclusive ownership of the MobileVM codebase, UI, and virtualization algorithms. Prohibits copying, cloning, or unauthorized distribution.",
            fullText = PROPRIETARY_COPYRIGHT_NOTICE,
            officialSourceUrl = "https://github.com/aistudio/mobilevm"
        ),
        SoftwareLicense(
            id = "linux_kernel_gplv2",
            name = "Linux Kernel",
            owner = "Linus Torvalds and Linux Kernel Contributors",
            licenseType = "GNU General Public License version 2.0 (GPLv2)",
            isThirdParty = true,
            summary = "The guest Linux kernel runs as an isolated, independent binary payload within the virtual machine. MobileVM does not modify or link proprietary code into the kernel.",
            fullText = """
                Linux Kernel is released under the GNU General Public License version 2 (GPLv2).
                
                GPL COMPLIANCE STATEMENT:
                MobileVM acts solely as a virtual hardware emulator (hypervisor). Guest operating
                system kernels (including Linux vmlinuz/Image binaries) run entirely in separate,
                isolated virtual memory address spaces. MobileVM does not bundle proprietary code
                into the Linux kernel source, nor does it statically link with Linux kernel modules.
                Kernel binaries are fetched directly from official distribution mirrors in compliance
                with section 3 of GPLv2. Complete corresponding source code for downloaded kernels
                is available through the respective distributions (kernel.org, Ubuntu Launchpad, Alpine apk).
            """.trimIndent(),
            officialSourceUrl = "https://www.kernel.org"
        ),
        SoftwareLicense(
            id = "ubuntu_canonical",
            name = "Ubuntu OS & Cloud Images",
            owner = "Canonical Ltd.",
            licenseType = "Canonical Intellectual Property & Trademark Policy",
            isThirdParty = true,
            summary = "Ubuntu is a trademark of Canonical Ltd. MobileVM downloads official images directly from Canonical infrastructure without modifying copyright notices.",
            fullText = """
                Ubuntu Intellectual Property and Trademark Notice:
                Ubuntu and Canonical are registered trademarks of Canonical Ltd.
                
                MobileVM interacts with Ubuntu images under Canonical's Intellectual Property Rights Policy:
                1. Official Images: All Ubuntu cloud images, kernels, and root filesystems are downloaded
                   directly and unmodified from official Canonical servers (cloud-images.ubuntu.com).
                2. Trademark Respect: MobileVM uses the name 'Ubuntu' purely to identify the guest OS
                   compatibility and does not claim endorsement or affiliation with Canonical Ltd.
                3. Individual package licenses within Ubuntu remain in effect as declared by their
                   upstream authors (including GPL, LGPL, BSD, and MIT).
            """.trimIndent(),
            officialSourceUrl = "https://ubuntu.com/legal/intellectual-property-policy"
        ),
        SoftwareLicense(
            id = "alpine_linux",
            name = "Alpine Linux",
            owner = "Alpine Linux Development Team",
            licenseType = "MIT / GPL / BSD Multi-license",
            isThirdParty = true,
            summary = "Alpine Linux rootfs and kernels are retrieved from official Alpine CDN in unmodified form.",
            fullText = """
                Alpine Linux is a security-oriented, lightweight Linux distribution based on musl libc and busybox.
                The core Alpine distribution components are provided under open source licenses including
                the MIT license, GPLv2, and BSD licenses.
                
                MobileVM retrieves Alpine release assets directly from official mirrors (dl-cdn.alpinelinux.org).
                All original license notices and package attributions are preserved.
            """.trimIndent(),
            officialSourceUrl = "https://alpinelinux.org"
        ),
        SoftwareLicense(
            id = "android_jetpack_kotlin",
            name = "Android Jetpack & Kotlin Coroutines",
            owner = "Google LLC & JetBrains s.r.o.",
            licenseType = "Apache License 2.0",
            isThirdParty = true,
            summary = "Licensed under the Apache License, Version 2.0.",
            fullText = """
                Licensed under the Apache License, Version 2.0 (the "License");
                you may not use this file except in compliance with the License.
                You may obtain a copy of the License at

                    http://www.apache.org/licenses/LICENSE-2.0

                Unless required by applicable law or agreed to in writing, software
                distributed under the License is distributed on an "AS IS" BASIS,
                WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
                See the License for the specific language governing permissions and
                limitations under the License.
            """.trimIndent(),
            officialSourceUrl = "https://www.apache.org/licenses/LICENSE-2.0"
        ),
        SoftwareLicense(
            id = "arm_architecture",
            name = "ARM Architecture Reference Manual",
            owner = "Arm Limited",
            licenseType = "Architectural Reference / Non-Proprietary Specification",
            isThirdParty = true,
            summary = "Virtual CPU instruction decoding conforms to publicly published Arm A64 ISA specifications.",
            fullText = """
                Arm and Cortex are registered trademarks of Arm Limited (or its subsidiaries) in the US and/or elsewhere.
                MobileVM implements a software interpreter for the publicly documented Armv8-A A64 instruction set
                for academic and mobile virtualization purposes without including proprietary Arm microcode or silicon IP.
            """.trimIndent(),
            officialSourceUrl = "https://developer.arm.com"
        )
    )

    /**
     * End User License Agreement (EULA).
     */
    val EULA_TEXT = """
        MOBILEVM END USER LICENSE AGREEMENT (EULA)

        1. GRANT OF LICENSE:
        The author grants you a revocable, non-exclusive, non-transferable, limited license to
        download, install, and execute MobileVM strictly for personal, non-commercial purposes
        on your authorized Android device.

        2. RESTRICTIONS ON USE:
        You agree that you will not:
        - Copy, clone, redistribute, or create unauthorized public distributions of this application.
        - Reverse engineer, decompile, modify, or strip security signatures from the binary APK.
        - Use the virtual machine to execute malware, unauthorized network intrusion tools,
          or activities that violate applicable state or federal laws.
        - Circumvent sandboxing or attempt unauthorized access to the host Android OS.

        3. COPYRIGHT & INTELLECTUAL PROPERTY:
        All copyrights, trade secrets, patents, and intellectual property rights in and to
        MobileVM are and shall remain the sole and exclusive property of the author.

        4. THIRD PARTY GUEST OPERATING SYSTEMS:
        Guest operating systems (such as Ubuntu or Alpine Linux) are subject to their respective
        official open source licenses. MobileVM does not claim ownership of guest OS software.

        5. DISCLAIMER OF WARRANTY:
        MOBILEVM IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
        INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE.
    """.trimIndent()
}
