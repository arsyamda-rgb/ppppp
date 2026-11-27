# Build error on GitHub: "Retrieving the default branch name ... Not Found"

```
Retrieving the default branch name
Not Found - https://docs.github.com/rest/repos/repos#get-a-repository
```
This text comes from `actions/checkout` (my reading of the log; the workflow file itself was not in the upload).
It asks GitHub for the repository's default branch and GitHub answers 404 = **the token used cannot see that repository**.
Typical cause here: a step `uses: actions/checkout` with `repository: <you>/VeyrionCharacter` (to build the dependency first).
The automatic `GITHUB_TOKEN` only reaches the repository the workflow runs in, so a second PRIVATE repo gives 404. A wrong owner/name gives the same 404.

Fix (pick one):
1. Put VeyrionCharacter and VeyrionWardrobe in ONE repository (two folders). Then no second checkout is needed.
2. Keep two repositories and give the checkout a token that can read the other one:
   create a fine-grained personal access token (Contents: read-only, only the VeyrionCharacter repo), save it as a secret
   `CHARACTER_REPO_TOKEN` in the Wardrobe repo, and use it in that checkout step (see below).
3. Skip checking it out: build VeyrionCharacter once, publish it to GitHub Packages/your own Maven repo, and point the pom at it.

Also needed in any case: VeyrionWardrobe's pom depends on `com.veyrion:veyrion-character:0.1.0`, which is in no public repository.
CI must run `mvn install` for VeyrionCharacter BEFORE `mvn package` for the Wardrobe.

```yaml
# .github/workflows/build.yml  (option 2)
name: build
on: [push, workflow_dispatch]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4                      # VeyrionWardrobe (this repo)
      - uses: actions/checkout@v4                      # VeyrionCharacter
        with:
          repository: YOUR_NAME/VeyrionCharacter       # exact owner/name
          token: ${{ secrets.CHARACTER_REPO_TOKEN }}   # PAT that can read it
          path: VeyrionCharacter
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 21, cache: maven }
      - run: mvn -B -f VeyrionCharacter/pom.xml install -DskipTests
      - run: mvn -B package
      - uses: actions/upload-artifact@v4
        with: { name: VeyrionWardrobe, path: target/VeyrionWardrobe-*.jar }
```
The `path:` of the second checkout must match the folder used in `-f`. If VeyrionCharacter's pom is not at its root, change `-f`.
