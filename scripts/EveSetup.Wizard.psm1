Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-EveCondition($Condition, [string]$Code) {
    if (!$Condition) { throw $Code }
}
function Assert-EveText([string]$Value, [int]$Maximum = 512) {
    Assert-EveCondition ($Value.Length -gt 0 -and $Value.Length -le $Maximum -and $Value -notmatch '[\x00-\x1f\x7f]') 'invalid_device_text'
}
function ConvertFrom-EveAdbDevices([string]$Text) {
    $seen = @()
    foreach ($line in ($Text -split '\r?\n')) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.Trim() -ceq 'List of devices attached') { continue }
        Assert-EveCondition ($line -cmatch '^([A-Za-z0-9][A-Za-z0-9._:\[\]-]*)\s+(\S+)(?:\s+(.*))?$') 'invalid_adb_devices_output'
        $deviceSerial = $Matches[1]; $state = $Matches[2]
        Assert-EveCondition ($deviceSerial.Length -le 256 -and $seen -cnotcontains $deviceSerial) 'ambiguous_adb_device'
        $seen += $deviceSerial
        [pscustomobject]@{ serial = $deviceSerial; state = $state }
    }
}
function ConvertTo-EveConnectAddress([string]$Value) {
    $inputValue = $Value.Trim()
    Assert-EveCondition ($inputValue -cmatch '^([0-9]{1,3}(?:\.[0-9]{1,3}){3})(?::([0-9]{1,5}))?$') 'enter_ipv4_and_port'
    $addressPart = $Matches[1]; $portPart = $Matches[2]
    $canonicalParts = @()
    foreach ($part in $addressPart.Split('.')) {
        Assert-EveCondition ([int]$part -le 255 -and ($part -eq '0' -or !$part.StartsWith('0'))) 'invalid_ipv4_address'
        $canonicalParts += [string][int]$part
    }
    $portValue = 5555
    if ($portPart) { $portValue = [int]$portPart }
    Assert-EveCondition ($portValue -ge 1 -and $portValue -le 65535) 'invalid_adb_port'
    return ($canonicalParts -join '.') + ':' + $portValue
}
function ConvertFrom-EvePackageSelection([string]$Value, [object[]]$Packages) {
    $inputValue = $Value.Trim()
    if (!$inputValue) { return }
    if ($inputValue -ieq 'все' -or $inputValue -ieq 'all') {
        foreach ($entry in $Packages) { [string]$entry.packageName }
        return
    }
    Assert-EveCondition ($inputValue -match '^[0-9]+(?:\s*[, ]\s*[0-9]+)*$') 'invalid_optional_selection'
    $selected = @()
    foreach ($part in ($inputValue -split '[, ]+' | Where-Object { $_ })) {
        $number = 0
        Assert-EveCondition ([int]::TryParse($part, [ref]$number) -and $number -ge 1 -and $number -le $Packages.Count) 'invalid_optional_selection'
        $name = [string]$Packages[$number - 1].packageName
        Assert-EveCondition ($selected -cnotcontains $name) 'duplicate_optional_selection'
        $selected += $name
    }
    return $selected
}
function Read-EveYesNo([string]$Prompt) {
    $answer = [string](Read-Host ($Prompt + ' [да/нет, по умолчанию нет]'))
    $answer = $answer.Trim()
    if (!$answer -or $answer -in @('нет', 'н', 'no', 'n')) { return $false }
    if ($answer -in @('да', 'д', 'yes', 'y')) { return $true }
    throw 'answer_yes_or_no'
}
function Get-EveLocalRoot([string]$Path) {
    if (!$Path) { $Path = Split-Path -Parent $PSScriptRoot }
    $fullPath = [IO.Path]::GetFullPath($Path)
    Assert-EveCondition (!$fullPath.StartsWith('\\')) 'local_kit_required'
    $directory = Get-Item -LiteralPath $fullPath -ErrorAction Stop
    Assert-EveCondition ($directory.PSIsContainer -and !($directory.Attributes -band [IO.FileAttributes]::ReparsePoint)) 'local_kit_required'
    return $directory.FullName
}
function Resolve-EveAdb([string]$RequestedPath, [string]$Root) {
    $candidates = @()
    if ($RequestedPath) { $candidates = @($RequestedPath) }
    else {
        $candidates = @('adb.exe', 'adb', (Join-Path $Root 'platform-tools/adb.exe'))
        foreach ($sdkRoot in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
            if ($sdkRoot) { $candidates += Join-Path $sdkRoot 'platform-tools/adb.exe' }
        }
        if ($env:LOCALAPPDATA) { $candidates += Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe' }
    }
    foreach ($candidate in $candidates) {
        $command = Get-Command -Name $candidate -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -eq $command -or $command.CommandType -notin @('Application', 'ExternalScript')) { continue }
        $file = Get-Item -LiteralPath $command.Source -ErrorAction Stop
        Assert-EveCondition (!$file.PSIsContainer -and !($file.Attributes -band [IO.FileAttributes]::ReparsePoint)) 'adb_must_be_local_file'
        return $file.FullName
    }
    throw 'adb_not_found'
}
function Invoke-EveAdb($Backend, [string]$AdbPath, [int]$Port, [string[]]$Arguments) {
    # Reuse the backend's bounded, argument-safe process runner for discovery.
    return & $Backend {
        param($toolPath, $toolArguments)
        Invoke-TvTool $toolPath $toolArguments 30
    } $AdbPath (@('-P', [string]$Port) + $Arguments)
}
function Invoke-EveBackend($Backend, [string]$Mode, [hashtable]$Options) {
    return & $Backend {
        param($backendMode, $backendOptions)
        Invoke-TvSetup $backendMode $backendOptions
    } $Mode $Options
}
function Read-EveLocalJson($Backend, [string]$Path) {
    return & $Backend { param($jsonPath) Read-TvJson $jsonPath } $Path
}
function Get-EveHash($Backend, [string]$Path) {
    return & $Backend { param($filePath) Get-TvFileHash $filePath } $Path
}
function Select-EveDevice($Backend, [string]$AdbPath, [int]$Port, [string]$Serial) {
    if ($Serial) { Assert-EveCondition ($Serial.Length -le 256 -and $Serial -cmatch '^[A-Za-z0-9][A-Za-z0-9._:\[\]-]*$') 'explicit_safe_serial_required' }
    $devices = @(ConvertFrom-EveAdbDevices (Invoke-EveAdb $Backend $AdbPath $Port @('devices', '-l')))
    if ($Serial) {
        $matches = @($devices | Where-Object { $_.serial -ceq $Serial })
        if ($matches.Count -eq 0 -and $Serial -match '^\d{1,3}(?:\.\d{1,3}){3}(?::\d{1,5})?$') {
            $Serial = ConvertTo-EveConnectAddress $Serial
            Write-Host ('Подключаюсь к ' + $Serial + '...')
            [void](Invoke-EveAdb $Backend $AdbPath $Port @('connect', $Serial))
            $devices = @(ConvertFrom-EveAdbDevices (Invoke-EveAdb $Backend $AdbPath $Port @('devices', '-l')))
        }
    } elseif ($devices.Count -eq 0) {
        Write-Host 'На ТВ включи отладку по сети. IP указан в настройках сети; порт — в настройках отладки.'
        Write-Host 'Если ТВ требует сопряжение с кодом, сначала выполни adb pair самостоятельно. Коды здесь не сохраняются.'
        $address = [string](Read-Host 'IP приставки, например 192.168.1.50:5555; пусто — отмена')
        if ([string]::IsNullOrWhiteSpace($address)) { return $null }
        $Serial = ConvertTo-EveConnectAddress $address
        Write-Host ('Подключаюсь к ' + $Serial + '...')
        [void](Invoke-EveAdb $Backend $AdbPath $Port @('connect', $Serial))
        $devices = @(ConvertFrom-EveAdbDevices (Invoke-EveAdb $Backend $AdbPath $Port @('devices', '-l')))
    }
    if (!$Serial) {
        if ($devices.Count -eq 1) { $Serial = $devices[0].serial }
        elseif ($devices.Count -gt 1) {
            Write-Host 'Подключено несколько устройств. Выбери нужную приставку:'
            for ($index = 0; $index -lt $devices.Count; $index++) { Write-Host ('  {0}. {1} [{2}]' -f ($index + 1), $devices[$index].serial, $devices[$index].state) }
            $choice = [string](Read-Host 'Номер устройства; пусто — отмена')
            if ([string]::IsNullOrWhiteSpace($choice)) { return $null }
            $number = 0
            Assert-EveCondition ([int]::TryParse($choice.Trim(), [ref]$number) -and $number -ge 1 -and $number -le $devices.Count) 'invalid_device_selection'
            $Serial = $devices[$number - 1].serial
        }
    }
    $selected = @($devices | Where-Object { $_.serial -ceq $Serial })
    Assert-EveCondition ($selected.Count -eq 1) 'device_not_found'
    if ($selected[0].state -ceq 'unauthorized') { throw 'device_unauthorized' }
    Assert-EveCondition ($selected[0].state -ceq 'device') 'device_offline_or_not_ready'
    return $Serial
}
function Get-EveIdentity($Backend, [string]$AdbPath, [int]$Port, [string]$Serial, [bool]$Emulator) {
    $prefix = @('-s', $Serial, 'shell')
    $qemu = Invoke-EveAdb $Backend $AdbPath $Port ($prefix + @('getprop', 'ro.kernel.qemu'))
    if ($Emulator) { Assert-EveCondition ($qemu -ceq '1') 'target_is_not_emulator' }
    else { Assert-EveCondition ($Serial -notmatch '^emulator-' -and $qemu -in @('', '0')) 'emulator_switch_required' }
    $model = Invoke-EveAdb $Backend $AdbPath $Port ($prefix + @('getprop', 'ro.product.model'))
    $fingerprint = Invoke-EveAdb $Backend $AdbPath $Port ($prefix + @('getprop', 'ro.build.fingerprint'))
    $sdk = Invoke-EveAdb $Backend $AdbPath $Port ($prefix + @('getprop', 'ro.build.version.sdk'))
    $user = Invoke-EveAdb $Backend $AdbPath $Port ($prefix + @('am', 'get-current-user'))
    Assert-EveText $model 128; Assert-EveText $fingerprint 512
    Assert-EveCondition ($sdk -cmatch '^[1-9][0-9]{0,2}$') 'invalid_sdk'
    Assert-EveCondition ($user -ceq '0') 'only_user_zero_supported'
    return [pscustomobject]@{ serial = $Serial; model = $model; fingerprint = $fingerprint; sdkInt = [int]$sdk; emulator = $Emulator; adbServerPort = $Port }
}
function New-EveBackendOptions($Identity, [string]$AdbPath) {
    return @{
        Serial = [string]$Identity.serial; ExpectedModel = [string]$Identity.model; ExpectedFingerprint = [string]$Identity.fingerprint
        Emulator = [bool]$Identity.emulator; AdbPath = $AdbPath; AdbServerPort = [int]$Identity.adbServerPort
        CommandTimeoutSeconds = 30; InstallTimeoutSeconds = 180; Apk = ''; ApkSha256 = ''; SigningCertSha256 = ''
        ApkSignerPath = 'apksigner'; AaptPath = 'aapt'; PinnedRelease = ''; ProfileId = 'generic-install-only'
        SetHome = $false; DisableStockHome = $false; DisablePackages = @(); PlanPath = ''; ApprovedPlanSha256 = ''; JournalPath = ''
    }
}
function Enter-EveWizardMutex([string]$Serial, [int]$Port) {
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { $key = ([BitConverter]::ToString($algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($Serial + ':' + $Port)))).Replace('-', '') }
    finally { $algorithm.Dispose() }
    $mutex = [Threading.Mutex]::new($false, ('Local\eve-box-setup-wizard-' + $key))
    try {
        $owned = $false
        try { $owned = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $owned = $true }
        Assert-EveCondition $owned 'another_eve_setup_operation_is_running'
        return $mutex
    } catch { $mutex.Dispose(); throw }
}
function Get-EveProfiles($Backend, $Release) {
    foreach ($record in @($Release.profiles)) {
        Assert-EveCondition ($record.id -cin @('generic-install-only', 'mi-box-s3-android14')) 'unexpected_pinned_profile'
        $path = Join-Path $PSScriptRoot ('setup-profiles/' + $record.id + '.json')
        $loaded = Read-EveLocalJson $Backend $path
        Assert-EveCondition ($loaded.Sha256 -ceq $record.sha256 -and $loaded.Value.id -ceq $record.id) 'pinned_profile_changed'
        $loaded.Value
    }
}
function Assert-EveNoPendingJournal($Backend, [string]$Root, $Identity) {
    $privateRoot = Join-Path $Root 'setup-private'
    if (!(Test-Path -LiteralPath $privateRoot)) { return }
    $privateDirectory = Get-Item -LiteralPath $privateRoot -ErrorAction Stop
    Assert-EveCondition ($privateDirectory.PSIsContainer -and !($privateDirectory.Attributes -band [IO.FileAttributes]::ReparsePoint)) 'invalid_private_setup_directory'
    # Only our direct GUID directories are considered; never traverse unrelated
    # paths, junctions or an arbitrary historical folder tree.
    foreach ($directory in @(Get-ChildItem -LiteralPath $privateRoot -Directory | Where-Object { $_.Name -cmatch '^[a-f0-9]{32}$' })) {
        $path = Join-Path $directory.FullName 'setup-journal.json'
        try {
            Assert-EveCondition (!($directory.Attributes -band [IO.FileAttributes]::ReparsePoint)) 'reparse_setup_directory'
            if (!(Test-Path -LiteralPath $path)) { continue }
            $saved = (Read-EveLocalJson $Backend $path).Value.plan.target
            Assert-EveText ([string]$saved.serial) 256
            Assert-EveCondition ($saved.serial -cmatch '^[A-Za-z0-9][A-Za-z0-9._:\[\]-]*$') 'invalid_journal_serial'
            $context = [pscustomobject]@{
                serial = $saved.serial; expectedModel = $saved.model; expectedFingerprint = $saved.fingerprint
                emulator = $saved.emulator; adbServerPort = $saved.adbServerPort; pinnedRelease = ''
            }
            # Read-TvJournal checks the original approved bytes and action
            # structure locally. This helper performs no live device queries.
            $journal = & $Backend { param($journalFile, $journalContext) Read-TvJournal $journalFile $journalContext } $path $context
        } catch {
            $failure = [InvalidOperationException]::new('invalid_owned_setup_journal')
            $failure.Data['JournalPath'] = $path
            throw $failure
        }
        if ($saved.serial -ceq $Identity.serial -and $saved.adbServerPort -eq $Identity.adbServerPort -and $journal.status -cnotin @('Applied', 'Restored')) {
            $failure = [InvalidOperationException]::new('pending_setup_transaction')
            $failure.Data['JournalPath'] = $path
            throw $failure
        }
    }
}
function Show-EvePlan($Plan, [string]$Path, $Release) {
    Write-Host ''
    Write-Host 'План установки Eve Box'
    Write-Host ('  Приставка: {0}, Android API {1}, {2}' -f $Plan.target.model, $Plan.target.sdkInt, $Plan.target.serial)
    Write-Host ('  APK: Eve Box {0} (code {1}).' -f $Release.apk.versionName, $Plan.apk.versionCode)
    if ($Plan.before.soft.installed) { Write-Host ('  Обновление code {0} → {1}; данные приложения сохраняются.' -f $Plan.before.soft.versionCode, $Plan.apk.versionCode) }
    else { Write-Host '  Новая установка приложения.' }
    if ($Plan.selection.setHome) { Write-Host ('  HOME: {0} → io.github.ekkzy.softlumi/io.github.ekkzy.softlumi.MainActivity' -f $Plan.before.homeComponent) }
    else { Write-Host ('  HOME сохраняется: ' + $Plan.before.homeComponent) }
    foreach ($entry in @($Plan.before.packages)) {
        if ($entry.state.enabledState -in @(2, 3, 4)) { Write-Host ('  Без изменений: {0}, уже отключено (состояние {1}).' -f $entry.packageName, $entry.state.enabledState) }
        else { Write-Host ('  Отключить для пользователя 0: ' + $entry.packageName) }
    }
    if (@($Plan.before.packages).Count -eq 0) { Write-Host '  Системные и необязательные приложения не отключаются.' }
    Write-Host '  Службы пульта, accessibility и таймеры сна не меняются.'
    Write-Host ('  План: ' + $Path)
    Write-Host '  Откат вернёт записанные состояния приложений и HOME; APK и данные приложения не откатываются.'
}
function Show-EveInspection($Inspection, $Journal) {
    Write-Host ('Приставка: {0}, Android API {1}, {2}' -f $Journal.plan.target.model, $Journal.plan.target.sdkInt, $Journal.plan.target.serial)
    Write-Host ('Статус журнала: ' + $Inspection.journalStatus)
    foreach ($action in @($Inspection.actions)) {
        $savedAction = @($Journal.actions | Where-Object { $_.id -ceq $action.id -and $_.kind -ceq $action.kind })
        Assert-EveCondition ($savedAction.Count -eq 1) 'inspection_action_mismatch'
        switch -CaseSensitive ($action.kind) {
            'Install' { Write-Host ('  Версия APK не откатывается; проверка состояния: ' + $action.observed) }
            'Launch' { }
            'Disable' {
                if ($action.needsRestore) { Write-Host ('  Вернуть {0}: состояние {1} → {2}.' -f $savedAction[0].packageName, $savedAction[0].desired, $savedAction[0].before.enabledState) }
                elseif ($action.observed -ceq 'Before') { Write-Host ('  Уже в исходном состоянии: ' + $savedAction[0].packageName) }
                else { Write-Host ('  Требует проверки: {0} ({1}).' -f $savedAction[0].packageName, $action.observed) }
            }
            'Home' {
                if ($action.needsRestore) { Write-Host ('  Вернуть HOME: ' + $Journal.plan.before.homeComponent) }
                elseif ($action.observed -ceq 'Before') { Write-Host ('  HOME уже исходный: ' + $Journal.plan.before.homeComponent) }
                else { Write-Host '  HOME изменён вне этой операции; автоматический возврат требует проверки.' }
            }
            default { throw 'unexpected_inspection_action' }
        }
    }
    if ($Inspection.installOutcomeUnknown) { Write-Host 'Результат установки неизвестен. Автоматический повтор установки и откат заблокированы.' }
    foreach ($conflict in @($Inspection.conflicts)) { Write-Host ('  Конфликт: ' + $conflict) }
    Write-Host ('Возврат настроек доступен: ' + $(if ($Inspection.canRestore) { 'да' } else { 'нет' }))
}
function Show-EveRecovery([string]$JournalPath) {
    Write-Host ('Сохрани журнал: ' + $JournalPath)
    Write-Host 'Проверить состояние: .\install.cmd -Mode Inspect'
    Write-Host 'Вернуть настройки:  .\install.cmd -Mode Restore'
    Write-Host 'В следующем запуске укажи путь к этому журналу. Не повторяй установку вслепую.'
}
function Get-EveErrorMessage([string]$Code) {
    switch -Wildcard ($Code) {
        'adb_not_found' { return 'ADB не найден. Добавь его в PATH, положи platform-tools рядом с install.cmd или укажи -AdbPath.' }
        'device_unauthorized' { return 'Разреши отладку на экране выбранной приставки, затем запусти мастер снова. Установка не началась.' }
        'device_offline_or_not_ready' { return 'Приставка offline или ещё не готова. Проверь подключение и авторизацию отладки.' }
        'device_not_found' { return 'Выбранная приставка не появилась в ADB. Проверь IP, порт и отладку. При необходимости сначала выполни adb pair.' }
        'emulator_switch_required' { return 'Выбран эмулятор. Для него требуется явный параметр -Emulator.' }
        'only_user_zero_supported' { return 'Поддерживается только основной пользователь Android (0). Изменения не начаты.' }
        'pinned_profile_changed' { return 'Профиль комплекта изменён. Возьми исходный комплект, не обходи проверку прошивки.' }
        'pinned_apk_*mismatch' { return 'APK не совпадает с проверенным выпуском. Установка не началась; распакуй исходный комплект заново.' }
        'approved_plan_hash_mismatch' { return 'План изменился после просмотра. Он не применён. Создай и проверь новый план.' }
        'journal_changed_since_preview' { return 'Журнал изменился после просмотра. Возврат не начат; запусти проверку журнала заново.' }
        '*state_changed_since_preview' { return 'Состояние приставки изменилось после просмотра. Этот план не применён; нужен новый просмотр.' }
        'plan_expired_create_new_preview' { return 'Плану больше 30 минут. Создай и проверь новый план.' }
        'downgrade_refused' { return 'На приставке установлена более новая версия. Понижение версии запрещено.' }
        'pending_setup_transaction' { return 'Для этой приставки уже есть незавершённая операция. Сначала проверь её журнал; новая установка не начата.' }
        'another_eve_setup_operation_is_running' { return 'Для этой приставки уже открыт другой мастер. Заверши или закрой его, затем запусти нужную операцию снова.' }
        'invalid_owned_setup_journal' { return 'Найден повреждённый или неподтверждённый журнал. Новая установка заблокирована; сохрани журнал для проверки.' }
        'enter_ipv4_and_port' { return 'Нужен IPv4-адрес приставки и необязательный порт, например 192.168.1.50:5555.' }
        'answer_yes_or_no' { return 'Нужно ответить «да» или «нет». Изменения не начаты.' }
        'tool_timeout:*' { return 'Команда ADB не завершилась вовремя. Установка автоматически не повторяется.' }
        default { return ('Проверка или операция остановлена: ' + $Code) }
    }
}
function Invoke-EveSetupWizard([hashtable]$Options) {
    $journalPath = ''; $planPath = ''; $planHash = ''; $wizardMutex = $null
    try {
        Assert-EveCondition ($Options.Mode -in @('Install', 'Inspect', 'Restore')) 'unsupported_mode'
        Assert-EveCondition (!$Options.PreviewOnly -or $Options.Mode -eq 'Install') 'preview_only_requires_install_mode'
        Assert-EveCondition ($Options.Mode -ne 'Install' -or !$Options.JournalPath) 'journal_path_requires_inspect_or_restore'
        $root = Get-EveLocalRoot $Options.KitRoot
        $backend = Import-Module (Join-Path $PSScriptRoot 'TvSetup.Common.psm1') -Force -PassThru -DisableNameChecking
        $adbPath = Resolve-EveAdb $Options.AdbPath $root
        Write-Host 'Eve Box — установка и возврат настроек'
        if ($Options.Mode -in @('Inspect', 'Restore')) {
            $journalPath = [string]$Options.JournalPath
            if (!$journalPath) { $journalPath = [string](Read-Host 'Путь к сохранённому setup-journal.json; пусто — отмена') }
            if ([string]::IsNullOrWhiteSpace($journalPath)) { return [pscustomobject]@{ status = 'Cancelled' } }
            $loadedJournal = Read-EveLocalJson $backend $journalPath.Trim()
            $journalPath = $loadedJournal.Path; $identity = $loadedJournal.Value.plan.target
            # These values choose the context only; the backend validates the
            # signed-off raw plan, journal shape and live identity before use.
            Assert-EveText ([string]$identity.serial) 256
            Assert-EveCondition (!$Options.Serial -or $Options.Serial -ceq $identity.serial) 'journal_target_mismatch'
            Assert-EveCondition (!$Options.AdbServerPortSpecified -or [int]$Options.AdbServerPort -eq [int]$identity.adbServerPort) 'journal_adb_port_mismatch'
            Assert-EveCondition ([bool]$Options.Emulator -eq [bool]$identity.emulator) 'journal_emulator_switch_mismatch'
            $wizardMutex = Enter-EveWizardMutex $identity.serial $identity.adbServerPort
            $backendOptions = New-EveBackendOptions $identity $adbPath
            $backendOptions.JournalPath = $journalPath
            $inspectionOutput = @(Invoke-EveBackend $backend 'Inspect' $backendOptions)
            Assert-EveCondition ($inspectionOutput.Count -ge 1 -and ([string]$inspectionOutput[0]).TrimStart().StartsWith('{')) 'invalid_backend_inspection'
            $inspection = ConvertFrom-Json -InputObject ([string]$inspectionOutput[0])
            Assert-EveCondition ((Get-EveHash $backend $journalPath) -ceq $loadedJournal.Sha256) 'journal_changed_since_preview'
            Show-EveInspection $inspection $loadedJournal.Value
            if ($Options.Mode -eq 'Inspect') { return [pscustomobject]@{ status = 'Inspected'; journalPath = $journalPath } }
            Assert-EveCondition $inspection.canRestore 'restore_requires_manual_reconciliation'
            Write-Host 'Вернутся только изменения из этого журнала. Установленная версия APK и данные останутся.'
            $answer = [string](Read-Host 'Чтобы вернуть настройки, введи ВЕРНУТЬ или RESTORE; пусто — отмена')
            if ($answer.Trim() -notin @('ВЕРНУТЬ', 'RESTORE')) { Write-Host 'Возврат отменён.'; return [pscustomobject]@{ status = 'Cancelled'; journalPath = $journalPath } }
            Assert-EveCondition ((Get-EveHash $backend $journalPath) -ceq $loadedJournal.Sha256) 'journal_changed_since_preview'
            [void](Invoke-EveBackend $backend 'Restore' $backendOptions)
            Write-Host 'Записанные состояния приложений и HOME возвращены. APK и данные не откатывались.'
            return [pscustomobject]@{ status = 'Restored'; journalPath = $journalPath }
        }

        $release = & $backend { Get-TvPinnedReleaseInfo -ReleaseId 'candidate.15' }
        Assert-EveCondition ($release.releaseId -ceq 'candidate.15' -and $release.recordVersion -eq 1) 'unsupported_pinned_release'
        Assert-EveCondition ([IO.Path]::GetFileName([string]$release.apk.file) -ceq $release.apk.file) 'invalid_pinned_apk_filename'
        $apkPath = Join-Path $root $release.apk.file
        $apkFile = Get-Item -LiteralPath $apkPath -ErrorAction Stop
        Assert-EveCondition (!$apkFile.PSIsContainer -and !($apkFile.Attributes -band [IO.FileAttributes]::ReparsePoint) -and $apkFile.Length -eq $release.apk.bytes) 'pinned_apk_size_mismatch'
        Assert-EveCondition ((Get-EveHash $backend $apkPath) -ceq $release.apk.sha256) 'pinned_apk_hash_mismatch'
        $profiles = @(Get-EveProfiles $backend $release)
        Assert-EveCondition ($profiles.Count -eq 2) 'incomplete_pinned_profiles'
        $selectedSerial = Select-EveDevice $backend $adbPath $Options.AdbServerPort $Options.Serial
        if (!$selectedSerial) { Write-Host 'Установка отменена.'; return [pscustomobject]@{ status = 'Cancelled' } }
        $identity = Get-EveIdentity $backend $adbPath $Options.AdbServerPort $selectedSerial ([bool]$Options.Emulator)
        $wizardMutex = Enter-EveWizardMutex $identity.serial $identity.adbServerPort
        Assert-EveNoPendingJournal $backend $root $identity
        Write-Host ('Выбрана приставка: {0}, Android API {1}, {2}' -f $identity.model, $identity.sdkInt, $identity.serial)
        $profile = @($profiles | Where-Object { $_.id -ceq 'generic-install-only' })
        Assert-EveCondition ($profile.Count -eq 1) 'missing_generic_profile'
        $profile = $profile[0]
        $exactProfile = @($profiles | Where-Object { $_.match.kind -ceq 'exact' -and $_.match.model -ceq $identity.model -and $_.match.fingerprint -ceq $identity.fingerprint -and $_.match.sdkInt -eq $identity.sdkInt })
        $setHome = $false; $disableStock = $false; $selectedPackages = @()
        if ($exactProfile.Count -eq 1) {
            $profile = $exactProfile[0]
            Write-Host 'Найден точный профиль Mi Box S 3 для этой прошивки.'
            $setHome = Read-EveYesNo 'Назначить Eve Box основным экраном HOME?'
            if ($setHome) {
                Write-Host 'Google TV Home можно отключить отдельно. Он станет недоступен до возврата настроек; данные не удаляются.'
                $disableStock = Read-EveYesNo 'Отключить Google TV Home при переходе на Eve Box?'
            }
            $available = @()
            foreach ($entry in @($profile.packages | Where-Object { $_.role -ceq 'optional' })) {
                $listed = Invoke-EveAdb $backend $adbPath $identity.adbServerPort @('-s', $identity.serial, 'shell', 'pm', 'list', 'packages', '--user', '0', $entry.packageName)
                if (@($listed -split '\r?\n' | Where-Object { $_.Trim() -ceq ('package:' + $entry.packageName) }).Count -eq 1) { $available += $entry }
            }
            if ($available.Count -gt 0) {
                Write-Host 'Необязательные приложения: выбранные будут отключены для пользователя 0. Пока ничего не выбрано.'
                for ($index = 0; $index -lt $available.Count; $index++) { Write-Host ('  {0}. {1} ({2})' -f ($index + 1), $available[$index].title, $available[$index].packageName) }
                Write-Host 'Отключённые приложения не запускаются. Отключение Xiaomi analytics не отключает телеметрию остальных приложений.'
                $selection = [string](Read-Host 'Номера через запятую, «все» / all или пусто — ничего не отключать')
                $selectedPackages = @(ConvertFrom-EvePackageSelection $selection $available)
            }
        } else { Write-Host 'Для этой модели или прошивки доступна только установка APK. HOME и приложения останутся без изменений.' }

        $transactionId = [guid]::NewGuid().ToString('N')
        $privateRoot = Join-Path $root 'setup-private'
        if (Test-Path -LiteralPath $privateRoot) {
            $privateDirectory = Get-Item -LiteralPath $privateRoot -ErrorAction Stop
            Assert-EveCondition ($privateDirectory.PSIsContainer -and !($privateDirectory.Attributes -band [IO.FileAttributes]::ReparsePoint)) 'invalid_private_setup_directory'
        }
        $transactionRoot = Join-Path $privateRoot $transactionId
        [void][IO.Directory]::CreateDirectory($transactionRoot)
        $planPath = Join-Path $transactionRoot 'setup-plan.json'
        $journalPath = Join-Path $transactionRoot 'setup-journal.json'
        $backendOptions = New-EveBackendOptions $identity $adbPath
        $backendOptions.PinnedRelease = $release.releaseId; $backendOptions.Apk = $apkPath; $backendOptions.ProfileId = $profile.id
        $backendOptions.SetHome = $setHome; $backendOptions.DisableStockHome = $disableStock; $backendOptions.DisablePackages = @($selectedPackages)
        $backendOptions.PlanPath = $planPath; $backendOptions.JournalPath = $journalPath
        [void](Invoke-EveBackend $backend 'Plan' $backendOptions)
        $loadedPlan = Read-EveLocalJson $backend $planPath
        $planHash = $loadedPlan.Sha256
        Show-EvePlan $loadedPlan.Value $planPath $release
        if ($Options.PreviewOnly) {
            Write-Host 'Только предварительный просмотр. Установка и изменения на приставке не выполнялись.'
            return [pscustomobject]@{ status = 'Previewed'; planPath = $planPath; planSha256 = $planHash }
        }
        $answer = [string](Read-Host 'Чтобы применить именно этот план, введи УСТАНОВИТЬ или YES; пусто — отмена')
        if ($answer.Trim() -notin @('УСТАНОВИТЬ', 'YES')) { Write-Host 'Установка отменена. На приставке ничего не изменено.'; return [pscustomobject]@{ status = 'Cancelled'; planPath = $planPath } }
        Assert-EveNoPendingJournal $backend $root $identity
        $backendOptions.ApprovedPlanSha256 = $planHash
        Write-Host 'Применяю подтверждённый план. Не закрывай окно и не отключай приставку.'
        [void](Invoke-EveBackend $backend 'Apply' $backendOptions)
        Write-Host 'Установка завершена: версия и хеш установленного APK проверены backend.'
        if ($loadedPlan.Value.selection.setHome) { Write-Host 'Назначение HOME и открытие лаунчера проверены. Проверь физическую кнопку HOME на пульте.' }
        Show-EveRecovery $journalPath
        return [pscustomobject]@{ status = 'Applied'; planPath = $planPath; planSha256 = $planHash; journalPath = $journalPath }
    } catch {
        if ($_.Exception.Data.Contains('JournalPath')) { $journalPath = [string]$_.Exception.Data['JournalPath'] }
        Write-Host (Get-EveErrorMessage $_.Exception.Message) -ForegroundColor Red
        if ($journalPath -and [IO.File]::Exists($journalPath)) { Show-EveRecovery $journalPath }
        elseif ($planPath -and [IO.File]::Exists($planPath)) { Write-Host ('Предварительный план сохранён здесь: ' + $planPath) }
        return [pscustomobject]@{ status = 'Failed'; errorCode = $_.Exception.Message; planPath = $planPath; journalPath = $journalPath }
    } finally {
        if ($null -ne $wizardMutex) {
            try { $wizardMutex.ReleaseMutex() } finally { $wizardMutex.Dispose() }
        }
    }
}

Export-ModuleMember -Function Invoke-EveSetupWizard, ConvertFrom-EveAdbDevices, ConvertTo-EveConnectAddress, ConvertFrom-EvePackageSelection
