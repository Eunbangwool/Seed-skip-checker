<#
  ESP32 펌웨어 컴파일 & 업로드 자동화
  사용법 (PowerShell에서):
    .\tools\flash.ps1              # 포트 자동감지 → 컴파일 → 업로드
    .\tools\flash.ps1 -CompileOnly # 컴파일만 (보드 없이 검증)
    .\tools\flash.ps1 -Port COM5   # 포트 직접 지정
    .\tools\flash.ps1 -Monitor     # 업로드 후 시리얼 모니터(115200)

  주의: 한글 사용자명(C:\Users\사용자) 때문에 ESP32 링커가 실패하므로
        데이터 폴더는 C:\arduino-data (ASCII), 빌드 폴더는 C:\kong_build (ASCII)로 고정한다.
#>
param(
  [string]$Port = "",
  [switch]$CompileOnly,
  [switch]$Monitor
)
$ErrorActionPreference = 'Stop'
$cli    = Join-Path $PSScriptRoot 'arduino-cli.exe'
$sketch = Join-Path (Split-Path $PSScriptRoot -Parent) 'firmware\kong_gyeolju'
$fqbn   = 'esp32:esp32:esp32'      # ESP32 Dev Module (WROOM-32 계열)
$bp     = 'C:\kong_build'          # ASCII 빌드 경로 (한글 경로 링커 오류 회피)
$cfgFile= 'C:\arduino-data\arduino-cli.yaml'

if (-not (Test-Path $cli))    { throw "arduino-cli 없음: $cli" }
if (-not (Test-Path $sketch)) { throw "스케치 폴더 없음: $sketch" }

# ASCII 데이터 폴더 설정이 있으면 사용
$cfgArgs = @()
if (Test-Path $cfgFile) { $cfgArgs = @('--config-file', $cfgFile) }
New-Item -ItemType Directory -Force $bp | Out-Null

Write-Host "== 컴파일 ==" -ForegroundColor Cyan
& $cli @cfgArgs compile --fqbn $fqbn --build-path $bp $sketch
if ($LASTEXITCODE -ne 0) { throw "컴파일 실패" }
Write-Host "컴파일 성공" -ForegroundColor Green
if ($CompileOnly) { return }

# 포트 자동감지
if (-not $Port) {
  Write-Host "== 포트 검색 ==" -ForegroundColor Cyan
  $j = & $cli @cfgArgs board list --format json | ConvertFrom-Json
  $cand = @()
  foreach ($p in $j.detected_ports) {
    if ($p.port.protocol -eq 'serial') { $cand += $p.port.address }
  }
  if ($cand.Count -eq 0) { throw "시리얼 포트를 못 찾음. ESP32를 USB로 연결했는지, CP210x/CH340 드라이버가 깔렸는지 확인. 또는 -Port COMx 로 지정." }
  if ($cand.Count -gt 1) { Write-Host "여러 포트: $($cand -join ', '). 첫 번째($($cand[0])) 사용. 다르면 -Port 로 지정." -ForegroundColor Yellow }
  $Port = $cand[0]
}
Write-Host "포트: $Port" -ForegroundColor Green

Write-Host "== 업로드 ==" -ForegroundColor Cyan
& $cli @cfgArgs upload -p $Port --fqbn $fqbn --input-dir $bp $sketch
if ($LASTEXITCODE -ne 0) { throw "업로드 실패 (연결/포트/드라이버 확인)" }
Write-Host "업로드 성공!" -ForegroundColor Green

if ($Monitor) {
  Write-Host "== 시리얼 모니터 (Ctrl+C 종료) ==" -ForegroundColor Cyan
  & $cli @cfgArgs monitor -p $Port -c baudrate=115200
}
