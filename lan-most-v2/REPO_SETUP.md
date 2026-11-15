# Publish to GitHub as a new repository

**Desired repository:** `rosergenbg-debug/lan-most-android` (Public or Private at owner's choice)

1. Open https://github.com/new in the GitHub account `rosergenbg-debug`.
2. Set repository name to **lan-most-android**. Create an **empty repository** (do not check README/.gitignore/license, already included here).
3. Upload every file and folder from this archive to the new repo, preserving nested folders, or use GitHub Desktop: File → Add Local Repository → select unzipped folder → initialize git if needed → Publish repository.
4. Push the `main` branch. GitHub Actions automatically builds a debug APK.
5. To build a permanently signed APK, configure four signing secrets described in README.md. Never upload the `.jks` or passwords as repository files.

The GitHub connector currently allows writing files to existing repositories but does not provide a create-repository operation. Therefore a newly named repo cannot be created directly until the owner creates its empty container.
