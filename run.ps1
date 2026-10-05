param([switch]$Demo)
$ErrorActionPreference = 'Stop'
# #Запуск: пересобирает исходники перед запуском, исключая использование устаревшего JAR.
& (Join-Path $PSScriptRoot 'build.ps1')
if ($Demo) {
    & java '-Dfile.encoding=UTF-8' -jar (Join-Path $PSScriptRoot 'dist/train-simulator.jar') --demo
} else {
    & java '-Dfile.encoding=UTF-8' -jar (Join-Path $PSScriptRoot 'dist/train-simulator.jar')
}
if ($LASTEXITCODE -ne 0) { throw 'Приложение завершилось с ошибкой.' }
