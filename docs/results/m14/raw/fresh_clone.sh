#!/bin/zsh
# M14(a): fresh clone -> stack up -> W1 scenario completed, timed. Caches (Gradle, Docker layers, JDK) are warm and
# the report says so. The main checkout's stack must be DOWN first (same ports).
set -u
S=${TMPDIR:-/tmp}/zsl-m14a; mkdir -p $S
D=$S/zsl-fresh
rm -rf $D
stamp() { date -u +%Y-%m-%dT%H:%M:%S.%3NZ 2>/dev/null || python3 -c 'import datetime;print(datetime.datetime.utcnow().isoformat()+"Z")'; }
t0=$(python3 -c 'import time;print(time.time())')
echo "T0 $(stamp) clone"
git clone -q https://github.com/ai-calypse/zerosum-ledger.git $D || { echo "clone failed"; exit 2; }
cd $D
echo "SHA $(git rev-parse HEAD)"
t1=$(python3 -c 'import time;print(time.time())'); echo "T1 $(stamp) make env"
make env > $S/m14a-env.log 2>&1 || { echo "make env failed"; exit 2; }
t2=$(python3 -c 'import time;print(time.time())'); echo "T2 $(stamp) make up"
make up > $S/m14a-up.log 2>&1 || { echo "make up failed"; tail -20 $S/m14a-up.log; exit 2; }
t3=$(python3 -c 'import time;print(time.time())'); echo "T3 $(stamp) W1 scenario"
./gradlew -q :tools:simulator:run --args="--scenario w1-trip-completed --runs 1 --seed 4242 --env-file $D/.env --out $S/m14a-out --label w1-fresh-clone" > $S/m14a-w1.log 2>&1
w1=$?
t4=$(python3 -c 'import time;print(time.time())'); echo "T4 $(stamp) done (simulator exit $w1)"
python3 - $t0 $t1 $t2 $t3 $t4 <<'EOF'
import sys
t=[float(x) for x in sys.argv[1:]]
names=['clone','make env','make up (build + start + healthy)','W1 scenario']
for n,a,b in zip(names,t,t[1:]): print(f'{n:40s} {b-a:8.1f} s')
print(f'{"TOTAL clone -> W1 completed":40s} {t[-1]-t[0]:8.1f} s  ({(t[-1]-t[0])/60:.2f} min)')
EOF
tail -3 $S/m14a-w1.log
