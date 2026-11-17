# Creates the key that signs release builds of Music Box Remote. Run once, yourself, in PowerShell:
#
#   powershell -ExecutionPolicy Bypass -File tools\create-signing-key.ps1
#
# It asks for a password, then writes two files to %USERPROFILE%\.musicbox-signing\ (outside the
# project, so they never go into git):
#   musicbox-release.p12   the key itself
#   keystore.properties    where the key is, and its password, for the Gradle build
#
# Back up that folder somewhere safe (and note the password). The Pixel will only accept updates
# signed with this same key; if it's lost, the app has to be uninstalled and installed afresh.

$ErrorActionPreference = 'Stop'
$keytool = 'C:\Android\SDK\jbr\bin\keytool.exe'
$dir = Join-Path $env:USERPROFILE '.musicbox-signing'
$keystore = Join-Path $dir 'musicbox-release.p12'
$props = Join-Path $dir 'keystore.properties'

if (Test-Path $keystore) {
    Write-Host "A signing key already exists at $keystore - leaving it alone."
    exit 1
}
if (-not (Test-Path $keytool)) {
    Write-Host "keytool not found at $keytool"
    exit 1
}

function Read-Secret([string]$prompt) {
    $secure = Read-Host $prompt -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

$password = Read-Secret 'Choose a password for the signing key (at least 8 characters)'
if ($password.Length -lt 8) {
    Write-Host 'Too short: use at least 8 characters.'
    exit 1
}
$again = Read-Secret 'Type it again'
if ($password -cne $again) {
    Write-Host "The two didn't match. Nothing was created."
    exit 1
}

New-Item -ItemType Directory -Force $dir | Out-Null

# Passed through an environment variable so the password doesn't appear on a command line.
$env:MUSICBOX_KEY_PASSWORD = $password
try {
    & $keytool -genkeypair -keystore $keystore -storetype PKCS12 -alias musicbox `
        -keyalg RSA -keysize 4096 -validity 36500 -dname 'CN=Music Box Remote' `
        -storepass:env MUSICBOX_KEY_PASSWORD -keypass:env MUSICBOX_KEY_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw "keytool failed (exit code $LASTEXITCODE)" }
} finally {
    Remove-Item Env:MUSICBOX_KEY_PASSWORD -ErrorAction SilentlyContinue
}

# Java properties format: backslashes must be doubled.
$escaped = $password.Replace('\', '\\')
$text = "storeFile=$($keystore.Replace('\', '/'))`nstorePassword=$escaped`nkeyAlias=musicbox`nkeyPassword=$escaped`n"
[IO.File]::WriteAllText($props, $text, (New-Object Text.UTF8Encoding $false))

Write-Host ''
Write-Host "Done. Signing key and settings saved in $dir"
Write-Host 'Back up that folder (both files) and keep the password somewhere safe, e.g. a password manager.'
