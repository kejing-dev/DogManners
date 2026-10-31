#!/usr/bin/env bash
# Screenshot tour of the design states (AGENTS.md "Screenshot list"). Usage: tools/qa_tour.sh OUTDIR [SHOT ...]
# Each line: NAME | commands separated by ';' | seconds to wait before the capture
OUT="${1:?OUTDIR}"; shift
D="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"
TOUR=$(cat <<'T'
S2_home|stare auto;lean auto;yaw 0;view 0;home|4
S2_home_P1|view -38|5
S3_disclaimer|firstRun|4
S12_settings_zh|lang zh;settings|4
S12_settings_en|lang en|4
S18_home_en|back|3
S10_guide_wary|lang zh;view 0;guide wary|5
S10_guide_play|guide play|5
S5_intro_L1|intro L1|3
S6_approach|stare auto;lean auto;yaw 0;view 0;lesson L1;dist 3.0|1
S7_wary|lesson L1;dist 2.4;dog wary|4
S7_wary_P1|view -38|4
S8_relaxed|view 0;stare auto;lean auto;yaw 0;lesson L1;dog relaxed;dist 1.4|3
S8_sniff|dog relaxed|3
S9_play|lesson L1;dog relaxed;dist 1.3|9
S14_L1_ok|view 40|8
S15_left|view 0;stare auto;lean auto;yaw 0;lesson L1;dist 2.2;dog wary|14
S15_left_P3|view 40|4
S16_too_close|view 0;stare auto;yaw 0;lesson L1;dog relaxed;dist 1.0;lean 0.4|2
S13_L2|stare auto;lean auto;yaw 0;lesson L2;view 40|5
S13_L3|lesson L3|5
S13_L4|lesson L4|5
S14_L4_bad|choose 1|6
S13_L5|lesson L5|5
S13_L6|lesson L6|5
S14_L6_ok|choose 1|5
S11_free|view 0;free 3|8
S17_muted|sound off;home;view -38|5
S18_L3_en|sound on;lang en;lesson L3;view 40|5
S18_wary_en|view -38;lesson L1;dog wary|6
F_test|view 0;lang zh;stare auto;lean auto;yaw 0;lesson L1;dist 1.6;ftest on|4
T
)
while IFS='|' read -r -u 3 name cmds wait; do
  [ -z "$name" ] && continue
  if [ $# -gt 0 ]; then keep=0; for s in "$@"; do [ "$s" = "$name" ] && keep=1; done; [ $keep = 0 ] && continue; fi
  IFS=';' read -ra parts <<< "$cmds"
  for c in "${parts[@]}"; do "$D/dev.sh" cmd "$c" >/dev/null < /dev/null; done
  sleep "$wait"
  "$D/dev.sh" shot "$name" "$OUT" >/dev/null < /dev/null
  st=$("$D/dev.sh" cmd dump < /dev/null | tail -1 | sed -E 's/.*state=([^ ]+).*/\1/')
  echo "$name -> $st" | tee -a "$OUT/tour.txt"
done 3<<< "$TOUR"
"$D/dev.sh" cmd 'ftest off' >/dev/null; "$D/dev.sh" cmd 'view 0' >/dev/null
