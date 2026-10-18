#!/usr/bin/env python3
"""Enforce production module boundaries without requiring another repository."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
allowed = {
    'api': set(), 'client': {'api'}, 'cli': {'client'}, 'domain': set(),
    'infrastructure': {'domain'}, 'presentation': {'api', 'domain'},
    'app': {'api', 'domain', 'infrastructure', 'presentation'},
}
for module, dependencies in allowed.items():
    build = (root / module / 'build.gradle.kts').read_text()
    # Test-only integration of the typed client and HTTP routes is intentional.
    build = re.sub(r'^.*testImplementation\(projects\..*$', '', build, flags=re.M)
    actual = set(re.findall(r'projects\.([A-Za-z]+)', build))
    assert actual == dependencies, f'{module}: expected {dependencies}, got {actual}'
    for source in (root / module / 'src').rglob('*.kt'):
        if any('Test' in part or part == 'test' for part in source.parts):
            continue
        for imported in re.findall(r'^import me\.nathanfallet\.teamcode\.([a-z]+)', source.read_text(), re.M):
            transitive = dependencies | {'api'} if module == 'cli' else dependencies
            assert imported == module or imported in transitive, f'{source}: forbidden import {imported}'
print('Production module boundaries verified.')
