# Start Shizuku on the box over network ADB (needed after every reboot; no root).
# Same steps as Shizuku's own start.sh: copy the starter out of the app and run it as shell.
. "$PSScriptRoot\lib.ps1"
$sz = 'moe.shizuku.privileged.api'
if (-not (Box shell "pm path $sz 2>/dev/null")) { Write-Host 'Shizuku is not installed on the box.'; exit 1 }
# Already up: leave it. Restarting it would cut OnnShell's link and the touchpad for a moment.
if (Box shell "ps -A -o USER,NAME | grep 'shell.*shizuku_server'") { Write-Host 'Shizuku is already running.'; exit 0 }
$cmd = 'LIB=$(dumpsys package ' + $sz + ' | grep -m1 legacyNativeLibraryDir | sed "s/.*legacyNativeLibraryDir=//"); ' +
       'cp "$LIB/arm/libshizuku.so" /data/local/tmp/shizuku_starter && chmod 700 /data/local/tmp/shizuku_starter && /data/local/tmp/shizuku_starter'
Box shell $cmd
Start-Sleep 2
if (Box shell "ps -A -o USER,NAME | grep 'shell.*shizuku_server'") { Write-Host 'Shizuku is running.' } else { Write-Host 'Shizuku did not start.'; exit 1 }

# Grant OnnShell Shizuku's permission. Ledger first, restore is pm revoke. Shizuku 13 also keeps its
# own allow list, so the first time it still asks once on the TV ("Allow all the time").
$perm = 'moe.shizuku.manager.permission.API_V23'
if (-not (Box shell "dumpsys package $Pkg | grep '${perm}: granted=true'")) {
    $restore = "pm revoke $Pkg $perm && am broadcast -n $Pkg/.admin.CommandReceiver -a $Pkg.LEDGER_DONE --es target shizuku-access"
    BoxCmd LEDGER_ADD "--es kind shell --es target shizuku-access --es restore '$restore'" | Out-Null
    Box shell pm grant $Pkg $perm
    Write-Host 'OnnShell may now use Shizuku.'
}
