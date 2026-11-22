#!/bin/sh
# Testy PC časti: Go testy (protokol, server, QR), logika ovládania na telefóne (priblíženie, gestá, klávesnica)
# a Java klient proti skutočnému PC programu s falošnou obrazovkou.
# Potrebuje Go 1.22+ a JDK 17. Spustenie: sh tools/run-pc-tests.sh
set -e
cd "$(dirname "$0")/.."
(cd pc && gofmt -l . | (! grep .) && go vet ./... && go test -count=1 ./...)
mkdir -p out/pc
(cd pc && go build -o ../out/pc/domov-pc-stub .)
javac -encoding UTF-8 -d out/pc app/src/main/java/sk/domov/ovladanie/pc/*.java tools/PcClientTest.java tools/PcLogicTest.java
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp out/pc PcLogicTest
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp out/pc PcClientTest out/pc/domov-pc-stub
