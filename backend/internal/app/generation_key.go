package app

import (
	"bytes"
	"io"
	"os"
	"syscall"
)

func checkGenerationKey(path string) bool {
	// NOFOLLOW and NONBLOCK avoid final-symlink races and blocking on special files.
	file, err := os.OpenFile(path, os.O_RDONLY|syscall.O_NOFOLLOW|syscall.O_NONBLOCK, 0)
	if err != nil {
		return false
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() {
		return false
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || (stat.Uid != 0 && stat.Uid != uint32(os.Geteuid())) {
		return false
	}
	mode := info.Mode().Perm()
	if mode&0007 != 0 || mode&0020 != 0 || (mode&0040 != 0 && stat.Gid != uint32(os.Getegid())) {
		return false
	}
	// Test readability explicitly even when tests run as root.
	if stat.Uid == uint32(os.Geteuid()) {
		if mode&0400 == 0 {
			return false
		}
	} else if stat.Gid != uint32(os.Getegid()) || mode&0040 == 0 {
		return false
	}
	key, err := io.ReadAll(io.LimitReader(file, 4097))
	if err != nil || len(key) > 4096 {
		return false
	}
	if bytes.HasSuffix(key, []byte("\n")) {
		key = bytes.TrimSuffix(key, []byte("\n"))
		key = bytes.TrimSuffix(key, []byte("\r"))
	}
	if len(key) == 0 {
		return false
	}
	for _, b := range key {
		if b < 33 || b > 126 {
			return false
		}
	}
	return true
}
