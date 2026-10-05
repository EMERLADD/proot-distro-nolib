.DEFAULT_GOAL := build

PROJECT_ROOT := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
ANDROID_SDK_ROOT ?= $(if $(ANDROID_HOME),$(ANDROID_HOME),$(HOME)/android-sdk)
NDK_PATH ?= $(if $(ANDROID_NDK_HOME),$(ANDROID_NDK_HOME),$(lastword $(sort $(wildcard $(ANDROID_SDK_ROOT)/ndk/*))))
OUT_DIR := $(PROJECT_ROOT)/build/proot-distro-nolib/arm64
PYTHON ?= python3
JOBS ?= 2

ifeq ($(origin CC),default)
CC := clang
endif
ifeq ($(origin AR),default)
AR := llvm-ar
endif

export NDK_PATH CC AR JOBS OUT_DIR

.PHONY: build test clean help

build:
	@sh "$(PROJECT_ROOT)/scripts/build-proot-nolib.sh"

test: build
	@PROOT_NOLIB_BINARY="$(OUT_DIR)/proot-distro-nolib" $(PYTHON) "$(PROJECT_ROOT)/tests/test_proot_nolib.py" -v
	@PROOT_NOLIB_BINARY="$(OUT_DIR)/proot-distro-nolib" $(PYTHON) "$(PROJECT_ROOT)/tests/test_pdn.py" -v

clean:
	rm -rf "$(PROJECT_ROOT)/build/proot-distro-nolib"

help:
	@printf '%s\n' 'make                 Build the ARM64 Android pdn and engine' 'make test            Build and run Android regression tests' 'make clean           Remove this engine build only' 'make NDK_PATH=...    Select an Android NDK installation' 'Output: $(OUT_DIR)/pdn and proot-distro-nolib'
