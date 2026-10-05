param([switch]$Test)
$ErrorActionPreference = 'Stop'
# #Сборка: компилирует UTF-8 исходники Java 17 и создаёт исполняемый JAR.
Push-Location -LiteralPath $PSScriptRoot
try {
    New-Item -ItemType Directory -Force -Path out, dist | Out-Null
    $taskSources = @(Get-ChildItem -LiteralPath 'src/train' -Filter '*.java' | ForEach-Object { $_.FullName })
    & javac --release 17 -encoding UTF-8 -d out @taskSources
    if ($LASTEXITCODE -ne 0) { throw 'Компиляция Java завершилась с ошибкой.' }
    # #ПоискJAR: в Windows Oracle добавляет java/javac в PATH, но иногда не добавляет jar.
    $taskJarCommand = Get-Command jar -ErrorAction SilentlyContinue
    if ($taskJarCommand) { $taskJar = $taskJarCommand.Source }
    else {
        # #ДиагностикаJDK: Java пишет настройки в stderr; PowerShell 5 не должен считать это аварией.
        $taskPreviousErrorPolicy = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $taskJavaSettings = & java -XshowSettings:properties -version 2>&1 | ForEach-Object { "$_" }
        } finally { $ErrorActionPreference = $taskPreviousErrorPolicy }
        $taskJavaHomeLine = $taskJavaSettings | Where-Object { $_ -match '^\s*java\.home\s*=' } | Select-Object -First 1
        if (-not $taskJavaHomeLine) { throw 'Не удалось определить каталог JDK.' }
        $taskJdkPath = ($taskJavaHomeLine -split '=', 2)[1].Trim()
        $taskJar = Join-Path $taskJdkPath 'bin/jar.exe'
        if (-not (Test-Path -LiteralPath $taskJar)) { throw 'Нужен полный JDK 17+, включая jar.' }
    }
    & $taskJar --create --file dist/train-simulator.jar --main-class train.Main -C out .
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось создать JAR.' }
    if ($Test) {
        & java '-Dfile.encoding=UTF-8' -cp out train.SelfTest
        if ($LASTEXITCODE -ne 0) { throw 'Проверки не пройдены.' }
    }
    Write-Host 'Готово: dist/train-simulator.jar'
} finally {
    # #ВосстановлениеКаталога: выполняется и при успешной сборке, и при ошибке.
    Pop-Location
}
