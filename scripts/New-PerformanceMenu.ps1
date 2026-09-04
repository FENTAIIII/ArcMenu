param(
    [ValidateSet(20, 100, 300)]
    [int]$Nodes = 100,
    [Parameter(Mandatory = $true)]
    [string]$Output
)

$resolvedOutput = [System.IO.Path]::GetFullPath($Output)
$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add('schema-version: 1')
$lines.Add("id: perf-$Nodes")
$lines.Add('canvas: {width: 400, height: 240, pixels-per-block: 100, distance: 3}')
$lines.Add('frontend:')
for ($index = 0; $index -lt $Nodes; $index++) {
    $column = $index % 20
    $row = [math]::Floor($index / 20)
    $x = -171 + $column * 18
    $y = 105 - $row * 14
    $z = 1 + ($index % 5) * 0.1
    $lines.Add("  node-${index}: {type: rectangle, width: 16, height: 12, color: '#335577', offset: {x: $x, y: $y, z: $z}}")
}
$lines.Add('backend:')
$lines.Add('  close: {x: 0, y: 0, width: 400, height: 240, actions: {shift-right: close}}')

$parent = Split-Path -Parent $resolvedOutput
if ($parent) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
[System.IO.File]::WriteAllLines($resolvedOutput, $lines, [System.Text.UTF8Encoding]::new($false))
Write-Output "Generated $resolvedOutput with $Nodes static frontend nodes."
