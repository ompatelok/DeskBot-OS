$mainActivityPath = "app/src/main/java/com/deskbot/os/MainActivity.kt"

$content = [System.IO.File]::ReadAllText($mainActivityPath)
$updatedContent = $content -replace "MqttManager\(this\)", "MqttManager.getInstance(this)"

[System.IO.File]::WriteAllText($mainActivityPath, $updatedContent, [System.Text.Encoding]::UTF8)
Write-Host "MainActivity.kt updated successfully to use MqttManager.getInstance(this)!" -ForegroundColor Green