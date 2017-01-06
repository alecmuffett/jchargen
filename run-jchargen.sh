#!/bin/sh
dir=`dirname $0`
cd $dir || exit 1
exec java JChargen "$@"
exit 1
