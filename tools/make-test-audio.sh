#!/usr/bin/env sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
ffmpeg -hide_banner -loglevel error -y -f lavfi \
  -i "sine=frequency=440:sample_rate=44100:duration=6.02" \
  -af "afade=t=in:d=0.02,afade=t=out:st=6:d=0.02" \
  -ac 1 -c:a aac -b:a 64k -map_metadata -1 -fflags +bitexact -flags:a +bitexact \
  "$ROOT/android/app/src/androidTest/assets/six-seconds.m4a"
