# Patches

pnpm applies these to dependencies at install (`patchedDependencies` in `pnpm-workspace.yaml`). They change
code of [StrykerJS](https://github.com/stryker-mutator/stryker-js), which is licensed under the Apache License 2.0.
They are used only for mutation testing during development and are not part of the popdesk image.

- `@stryker-mutator__core@10.0.0.patch`: with `--testFiles`, keeps static mutants off while tests run, so
  constants are mutated as configured.
- `@stryker-mutator__vitest-runner@10.0.0.patch`: prints results that hold circular references without failing.
