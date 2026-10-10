# Downloads the runtime components that ship inside the APK (app/bundled-assets, not in git) and checks their hashes.
# These are the exact files GameNative fetches on first launch; bundling them makes the first launch offline.
param(
	[string]$Dest = (Join-Path $PSScriptRoot "..\app\bundled-assets")
)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$files = @(
	@{ Name = "imagefs_bionic.txz"; Url = "https://downloads.gamenative.app/imagefs_bionic.txz"; Sha256 = "368db62bfc58b72c97e5169bda9aa64d4246f07964c447e27c79a065e7e9c48b" },
	@{ Name = "bundled/proton-10.0-arm64ec.wcp"; Url = "https://downloads.gamenative.app/proton-10.0-arm64ec.wcp"; Sha256 = "c1638b1f830304222660e338bc032099145ad210d4e36a07d91f39c750a062f7" },
	@{ Name = "bundled/Turnio_v26.2.0_R4.zip"; Url = "https://downloads.gamenative.app/drivers/Turnio_v26.2.0_R4.zip"; Sha256 = "2f61896810e82aa9aa309b852326261e90bd2b9bfbb52f47ab036b65afd7524d" }
)

New-Item -ItemType Directory -Force (Join-Path $Dest "bundled") | Out-Null
foreach ($f in $files) {
	$path = Join-Path $Dest $f.Name
	if ((Test-Path $path) -and (Get-FileHash $path -Algorithm SHA256).Hash.ToLower() -eq $f.Sha256) {
		Write-Host "ok       $($f.Name)"
		continue
	}
	Write-Host "download $($f.Name)"
	Invoke-WebRequest $f.Url -OutFile $path
	$hash = (Get-FileHash $path -Algorithm SHA256).Hash.ToLower()
	if ($hash -ne $f.Sha256) {
		Remove-Item $path
		throw "SHA-256 mismatch for $($f.Name): $hash"
	}
}
