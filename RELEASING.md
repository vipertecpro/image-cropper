# Releasing

How to cut a new release. **Never move an already-published tag** — Packagist
freezes published versions, so always bump to a new number.

## Pick the version number (SemVer)

Given the current `x.y.z`:

- **Patch** (`x.y.Z`) — bug fixes only, no API change. e.g. `1.0.2 → 1.0.3`
- **Minor** (`x.Y.0`) — new features, backwards-compatible. e.g. `1.0.3 → 1.1.0`
- **Major** (`X.0.0`) — breaking changes. e.g. `1.1.0 → 2.0.0`

## Steps

1. **Make & commit your changes**, and make sure tests pass:
   ```bash
   vendor/bin/pest
   ```

2. **Update `CHANGELOG.md`** — add a new section at the top with dated
   `### Added` / `### Changed` / `### Fixed` bullet points:
   ```markdown
   ## [1.0.3] - YYYY-MM-DD
   ### Fixed
   - Short, specific description of what changed and why.
   ```

3. **Bump the version** in `nativephp.json` (`"version": "1.0.3"`).

4. **Commit** the changelog + version bump:
   ```bash
   git commit -am "Release v1.0.3"
   ```

5. **Tag and push** (annotated tag, matching the number):
   ```bash
   git tag -a v1.0.3 -m "v1.0.3"
   git push origin main
   git push origin v1.0.3
   ```

6. **Create the GitHub release** (notes = the changelog section):
   ```bash
   gh release create v1.0.3 --latest --title "v1.0.3 — <summary>" \
     --notes "**Fixed**
   - …"
   ```

7. **Packagist** auto-updates via the GitHub webhook (usually within seconds).
   Confirm the new version appears at
   <https://packagist.org/packages/vipertecpro/image-cropper>.

8. **Consume it** in an app that requires `^1.0` (or `^1.1`, etc.):
   ```bash
   composer update vipertecpro/image-cropper
   ```
   The `^1.0` constraint stays as-is — Composer resolves it to the newest
   matching version and records the exact one in `composer.lock`.

## Rules of thumb

- A published version is a permanent contract: `1.0.2` must always be the same
  code for everyone. If you shipped a bad release, **release a fix** (`1.0.3`),
  don't re-tag.
- Keep changelog entries short and specific — one line per change, focused on
  *what changed and why it matters to a user*.
