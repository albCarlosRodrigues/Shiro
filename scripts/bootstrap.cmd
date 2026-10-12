@echo off
rem Squad Desktop - prepara o ambiente de desenvolvimento (duplo clique ou execute num terminal).
setlocal
where node >nul 2>nul
if errorlevel 1 (
  echo Node.js nao foi encontrado no PATH. Instale o Node 22 ou 24 LTS em https://nodejs.org e rode de novo.
  pause
  exit /b 1
)
node "%~dp0bootstrap.mjs" %*
echo.
pause
