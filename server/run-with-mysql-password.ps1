$dbUser = if ($env:LMS_DB_USER) { $env:LMS_DB_USER } else { 'root' }
$securePassword = Read-Host "Enter the MySQL password for $dbUser (press Enter if blank)" -AsSecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $env:LMS_DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    & java "-Dlibrary.root=$((Get-Location).Path)" '-Dlibrary.openBrowser=true' '-cp' 'server\classes;server\lib\*' 'LibraryServer'
    exit $LASTEXITCODE
}
finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    Remove-Item Env:LMS_DB_PASSWORD -ErrorAction SilentlyContinue
}
