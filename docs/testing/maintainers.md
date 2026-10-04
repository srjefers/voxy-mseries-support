# GitHub setup for public testing

These are the steps the repository owner does once on GitHub. Steps 1 to 4
are required, in this order. Steps 5 and 6 are optional. PR #21 must be
merged into `dev` before step 2.

| # | Step | Required | gh CLI |
|---|---|---|---|
| 1 | Create the `testing` label | Yes | Yes |
| 2 | Merge the testing kit into `dev` | Yes | Yes (PR) |
| 3 | Publish a pre-release with the jar | Yes | Yes |
| 4 | Post the call for testers and pin it | Yes | Yes |
| 5 | Turn on Discussions with a Testing category | Optional | Partly |
| 6 | Track coverage in a public Project | Optional | Partly |

Before you start, check that gh uses the right account:

```bash
gh auth status            # the active account must be srjefers
gh auth switch -u srjefers   # if another account is active
```

Every command below names the repository with `-R`, so you can run it from
any folder. Commands that use `git` or `./gradlew` run from the repo root.

## 1. Create the `testing` label (required)

The issue form adds this label to every report. GitHub skips a label that
does not exist, so create it before step 2.

```bash
gh label create testing -R srjefers/voxy-mseries-support \
  --color 1D76DB \
  --description "Test reports from players and developers"
```

Web UI: *Issues > Labels > New label*. Name `testing`, color `#1D76DB`.

## 2. Merge the testing kit into `dev` (required)

GitHub reads issue forms only from the default branch, `dev`. `dev` is
push-protected, so the kit goes in through a PR. The kit is part of PR #21,
so merging #21 is this step. The guides link to README sections, docs and
log lines that #21 adds, so they belong in the same merge.

Files the kit adds:

- `.github/ISSUE_TEMPLATE/test-report.yml`: the report form.
- `.github/ISSUE_TEMPLATE/config.yml`: keeps blank issues and adds a
  "Testing guide" link to the new-issue page.
- `.github/DISCUSSION_TEMPLATE/testing.yml`: does nothing until step 5.
- `scripts/testing/`: the 4 scripts, `lib.sh` and `README.md`.
- `docs/testing/README.md`: the public guide. The form and `config.yml`
  link to it.
- `docs/testing/player-track.md`: the step-by-step guide for players.
- `docs/testing/developer-track-part1.md`, `-part2.md` and `-part3.md`:
  the full test plan for developers.
- `docs/testing/call-for-testers.md`: the text for step 4.
- `docs/testing/maintainers.md`: this file.

```bash
gh pr merge 21 -R srjefers/voxy-mseries-support --merge
```

Web UI: open PR #21 and use *Merge pull request*.

## 3. Publish a pre-release with the jar (required)

Players need a public download. Actions artifacts need repo access and
expire after 90 days, so use a GitHub Release marked as a pre-release.

Build from `dev` after step 2. It then holds the code of #21 and the kit.
The kit adds only docs and scripts, so it does not change the mod's code.

Build from a clean commit that is already on GitHub. Otherwise the jar name
ends in `-dirty` and the tag points at a commit testers cannot see.

```bash
git switch dev
git pull                                  # the commit must be on GitHub
git status --short --untracked-files=no   # must print nothing
rm -f build/libs/voxy-*.jar              # old jars keep other names
./gradlew build
JAR=$(ls build/libs/voxy-*.jar)
echo "$JAR"                               # one file, no -dirty
shasum -a 256 "$JAR"                      # paste into the notes below
```

Save the release notes to a file outside the repo, for example
`~/voxy-release-notes.md`:

```markdown
Test build for the public testing round. Alpha software: use a separate
launcher profile and back up your worlds.

**Requirements:** Apple Silicon Mac, macOS 13 or newer, Minecraft 1.21.11,
Fabric Loader 0.18.2, Fabric API 0.140.0+1.21.11, Sodium mc1.21.11-0.8.1
(exactly). Iris 1.10.7 is optional. Do not use voxyworldgenv2 2.2.2.

**JVM arguments:** `-Xmx6G -Dvoxy.forceMetal=true` (`-Xmx4G` on a 16 GB
Mac). Without `-Dvoxy.forceMetal=true`, Voxy turns itself off.

**How to test:** [testing guide](https://github.com/srjefers/voxy-mseries-support/blob/dev/docs/testing/README.md).
**Report results:** [test report form](https://github.com/srjefers/voxy-mseries-support/issues/new?template=test-report.yml).

SHA-256: <paste the shasum output>
```

Create it as a draft, check it, then publish:

```bash
TAG=v0.2.8-alpha-test1
gh release create "$TAG" "$JAR" -R srjefers/voxy-mseries-support \
  --target "$(git rev-parse HEAD)" \
  --prerelease --draft \
  --title "Voxy 0.2.8-alpha, test build 1" \
  --notes-file ~/voxy-release-notes.md
gh release view "$TAG" -R srjefers/voxy-mseries-support --web   # review
gh release edit "$TAG" -R srjefers/voxy-mseries-support --draft=false
```

Web UI: *Code > Releases > Draft a new release*. Type the new tag, pick the
commit as *Target*, add the title and notes, drag the jar in, tick *Set as a
pre-release*, then *Save draft* and later *Publish release*.

Then put the release URL in place of "link to be added" in
`docs/testing/call-for-testers.md`. The guides link to the Releases page, so
they need no change.

The reporter of issue #18 hand-patched an old jar. You can point them to
this release.

## 4. Post the call for testers and pin it (required)

Post it as an issue. This needs no settings change, and anyone can comment.
Delete the two `<!-- -->` lines at the top of
`docs/testing/call-for-testers.md` first.
GitHub hides them, but they are notes for you, not for testers.

Run it from the repository root.

```bash
gh issue create -R srjefers/voxy-mseries-support \
  --title "Call for testers: Voxy far terrain on Apple Silicon Macs" \
  --label "help wanted" \
  --body-file docs/testing/call-for-testers.md
gh issue pin <number> -R srjefers/voxy-mseries-support
```

Web UI: *Issues > New issue > Blank issue*, paste the text, *Create*. Then
*Pin issue* in the issue's right sidebar. A repo can pin up to 3 issues.

## 5. Turn on Discussions with a Testing category (optional)

Do this later, if testers ask many setup questions in the pinned issue.
Reports stay as issues: the form adds the label, and Projects cannot track
discussions.

1. Turn on Discussions.

   ```bash
   gh repo edit srjefers/voxy-mseries-support --enable-discussions
   ```

   Web UI: *Settings > General > Features > Discussions*.

2. Create the category. Web UI only: *Discussions*, then the pencil next to
   *Categories*, then *New category*. Name `Testing`, format *Question and
   answer*. The name must give the slug `testing`, because the form file is
   `.github/DISCUSSION_TEMPLATE/testing.yml`.

3. Link it from the new-issue page. Add this to
   `.github/ISSUE_TEMPLATE/config.yml` through a PR to `dev`:

   ```yaml
     - name: Testing questions
       url: https://github.com/srjefers/voxy-mseries-support/discussions/categories/testing
       about: Ask about setup, launchers or the scripts. Report results with the Test report form.
   ```

4. Optional: post the call for testers in *Announcements* too, and pin it.
   Web UI only: *Discussions > New discussion > Announcements*, then *Pin
   discussion* in the sidebar. Only maintainers can start announcements, and
   anyone can reply.

## 6. Track coverage in a public Project (optional)

A Project shows which tests ran on which Macs, and new reports are added
to it automatically. The simpler option is the *Coverage* table that
`docs/testing/README.md` already has. You fill it by hand, and each update
needs a PR because `dev` is push-protected.

The gh token needs the `project` scope first:

```bash
gh auth refresh -s project
gh project create --owner srjefers --title "Voxy test coverage"
# Note the project number it prints, then:
N=<number>
gh project edit "$N" --owner srjefers --visibility PUBLIC
gh project link "$N" --owner srjefers --repo srjefers/voxy-mseries-support
gh project field-create "$N" --owner srjefers --name "Test ID" --data-type TEXT
gh project field-create "$N" --owner srjefers --name "Result" \
  --data-type SINGLE_SELECT --single-select-options "Pass,Fail,Partial,Blocked"
gh project field-create "$N" --owner srjefers --name "Chip" --data-type TEXT
```

Auto-add new reports. Web UI only: open the Project, then *... > Workflows >
Auto-add to project*. Pick `voxy-mseries-support`, set the filter
`is:issue label:testing`, save and turn it on. GitHub Free allows one
auto-add workflow. It does not add old issues. Add those by hand:

```bash
gh project item-add "$N" --owner srjefers \
  --url https://github.com/srjefers/voxy-mseries-support/issues/<number>
```

The form does not fill Project fields. Set Test ID, Result and Chip when you
read each report.

## After launch

- Read each report. For a Fail you can reproduce, add the bug label. The
  label name includes the emoji:
  `gh issue edit <number> -R srjefers/voxy-mseries-support --add-label "🐛 bug"`.
- Close a report once its result is recorded. Keep bug issues open until
  the fix ships in a new pre-release.
- For each new test build, repeat step 3 with the next tag
  (`v0.2.8-alpha-test2`) and update the link in the pinned issue.
