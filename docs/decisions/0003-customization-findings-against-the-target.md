# 0003: Customization findings are computed against the target package from rules kept as data

**Context.** Issues #4, #5, #6, #7, #8 and #10 each ask the same question of a different kind of customization: what becomes of this when the server moves to the target version? Their answers depend on two things the tool did not have:

- a view of the target package's contents (its jars, their coordinates and the classes they hold, its files);
- the facts that change between release lines (a jar the target replaces, a file that moved, a construct the target no longer accepts).

The NGRA guide worked all of this out by hand.

**Decision.**

- `PackageIndex` reads a webapp, as a directory or a WAR, in one streaming pass. Nested jars are read inside the WAR stream and nothing is unpacked or loaded. The index holds:
  - every file;
  - every `WEB-INF/lib` jar, with its coordinates from an embedded `pom.properties`, else from its file name, else none (never guessed);
  - which jar holds each class.
- The facts between release lines are data in `compat/matrix.yaml`, which moves to version 3: `jarRules` (#4), `relocations` (#6) and `constructs` (#8). Each rule applies between a `from` and a `to` version range and names its `source`. Paths are globs, and patterns are regular expressions. Adding a rule for a new release line is a data change, not code.
- The findings appear in two places:
  - `customizations scan --vendor <running version's distribution> --target <target distribution>` reports them for every file the scan finds changed or added. With `--merge-dir` it also writes a three-way merge for each changed file the target ships too.
  - The upgrade plan reports them for the registered customizations, and the reconcile step says where a relocated setting went.
- The three-way merge is jrs-hotfix's line merge (`Diff`, `Diff3`, `Text`), ported into `ops.merge`. This adds no dependency.

**Consequences.** Findings are only as good as the rules. The bundled rules come from the NGRA guide as the issues quote it. Each cites that guide's section, and a vendor guide page still has to be added where the issue asks for one. A WAR is read once per scan, which takes seconds for a 300 MB WAR; the plan reads the target only when a registered customization needs it.
