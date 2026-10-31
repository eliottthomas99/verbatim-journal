# Synthetic speech fixtures only. No microphone access or personal audio.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
$directory = Join-Path $PSScriptRoot '..\app\build\generated\whistle-test-assets'
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(
    16000,
    [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
    [System.Speech.AudioFormat.AudioChannel]::Mono
)
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    $synth.SelectVoice('Microsoft David Desktop')
    $synth.SetOutputToWaveFile((Join-Path $directory 'english.wav'), $format)
    $synth.Speak('This is a local speech recognition test. The model should detect English automatically.')
    $synth.SetOutputToNull()
    $synth.SelectVoice('Microsoft Hortense Desktop')
    $synth.SetOutputToWaveFile((Join-Path $directory 'french.wav'), $format)
    $synth.Speak('Bonjour, ceci est un test de reconnaissance vocale. Le fichier reste sur le t' + [char]0xe9 + 'l' + [char]0xe9 + 'phone.')
} finally {
    $synth.Dispose()
}
Write-Output "Generated 16 kHz mono test WAV files in $directory"
