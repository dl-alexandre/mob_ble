MIX_APP_PATH ?= _build/dev/lib/mob_ble
PRIV_DIR ?= $(MIX_APP_PATH)/priv
NIF := $(PRIV_DIR)/mob_ble_nif.so

ERTS_INCLUDE_DIR ?= $(shell erl -noshell -eval 'io:format("~s/erts-~s/include", [code:root_dir(), erlang:system_info(version)]), halt().')
CC ?= cc
CFLAGS ?= -O2
CFLAGS += -fPIC -I$(ERTS_INCLUDE_DIR)

UNAME_S := $(shell uname -s)
ifeq ($(UNAME_S),Darwin)
	LDFLAGS += -dynamiclib -undefined dynamic_lookup
else
	LDFLAGS += -shared
endif

.PHONY: all clean

all: $(NIF)

$(NIF): priv/native/mob_ble_nif_stub.c
	mkdir -p $(PRIV_DIR)
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $<

clean:
	rm -f $(NIF)
