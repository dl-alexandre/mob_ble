// build.zig - Android static-NIF object build for the mob_ble plugin.
//
// This does not produce a standalone .so. It produces a plugin-owned static
// archive that the consuming Mob application's final lib<app>.so link can
// consume without compiling plugin C sources directly.

const std = @import("std");

pub fn build(b: *std.Build) void {
    const abi = required(b, "abi", "Android ABI: arm64-v8a or armeabi-v7a");
    const otp_dir = required(b, "otp_dir", "Path to the Android OTP runtime");
    const erts_vsn = required(b, "erts_vsn", "ERTS version dir name, e.g. erts-17.0");
    const ndk_sysroot = required(b, "ndk_sysroot", "Path to the NDK sysroot");
    const mob_dir = b.option([]const u8, "mob_dir", "Optional path to mob library") orelse "";
    const archive_name = b.option([]const u8, "archive_name", "Output static archive name") orelse "libmob_ble_android.a";

    const target_query = abiToTarget(abi) orelse {
        std.debug.print("unsupported -Dabi={s}; expected arm64-v8a or armeabi-v7a\n", .{abi});
        std.process.exit(1);
    };
    const target = b.resolveTargetQuery(target_query);
    const arch_triple = ndkArchTriple(abi);

    var flags = std.ArrayList([]const u8).empty;
    defer flags.deinit(b.allocator);
    flags.appendSlice(b.allocator, &.{
        "-Os",
        "-ffunction-sections",
        "-fdata-sections",
        "-fPIC",
        "-DSTATIC_ERLANG_NIF",
        "-DSTATIC_ERLANG_NIF_LIBNAME=mob_ble_nif",
        b.fmt("--sysroot={s}", .{ndk_sysroot}),
        "-isystem",
        b.fmt("{s}/usr/include", .{ndk_sysroot}),
        "-isystem",
        b.fmt("{s}/usr/include/{s}", .{ ndk_sysroot, arch_triple }),
        "-I",
        b.fmt("{s}/{s}/include", .{ otp_dir, erts_vsn }),
    }) catch @panic("OOM");

    if (mob_dir.len > 0) {
        flags.appendSlice(b.allocator, &.{ "-I", b.fmt("{s}/android/jni", .{mob_dir}) }) catch @panic("OOM");
    }

    var emitted = std.ArrayList(std.Build.LazyPath).empty;
    defer emitted.deinit(b.allocator);

    const sources = [_][]const u8{
        "mob_ble_nif.c",
        "mob_ble_jni_hooks.c",
    };

    for (sources) |source| {
        const name = source[0 .. source.len - 2];
        const mod = b.createModule(.{
            .target = target,
            .optimize = .ReleaseSmall,
        });
        mod.addCSourceFile(.{
            .file = b.path(source),
            .flags = flags.items,
        });

        const obj = b.addObject(.{
            .name = name,
            .root_module = mod,
        });

        const out = obj.getEmittedBin();
        emitted.append(b.allocator, out) catch @panic("OOM");
        const install = b.addInstallFile(out, b.fmt("{s}/{s}.o", .{ abi, name }));
        b.default_step.dependOn(&install.step);
    }

    const llvm_ar = b.fmt("{s}/../bin/llvm-ar", .{ndk_sysroot});
    const archive = b.addSystemCommand(&.{ llvm_ar, "rcs" });
    const archive_out = archive.addOutputFileArg(archive_name);
    for (emitted.items) |object| {
        archive.addFileArg(object);
    }

    const install_archive = b.addInstallFile(archive_out, b.fmt("{s}/{s}", .{ abi, archive_name }));
    b.default_step.dependOn(&install_archive.step);
}

fn required(b: *std.Build, name: []const u8, description: []const u8) []const u8 {
    return b.option([]const u8, name, description) orelse {
        std.debug.print("missing required -D{s}: {s}\n", .{ name, description });
        std.process.exit(1);
    };
}

fn abiToTarget(abi: []const u8) ?std.Target.Query {
    if (std.mem.eql(u8, abi, "arm64-v8a")) {
        return .{ .cpu_arch = .aarch64, .os_tag = .linux, .abi = .android };
    }
    if (std.mem.eql(u8, abi, "armeabi-v7a")) {
        return .{ .cpu_arch = .arm, .os_tag = .linux, .abi = .androideabi };
    }
    return null;
}

fn ndkArchTriple(abi: []const u8) []const u8 {
    if (std.mem.eql(u8, abi, "arm64-v8a")) return "aarch64-linux-android";
    if (std.mem.eql(u8, abi, "armeabi-v7a")) return "arm-linux-androideabi";
    return abi;
}
