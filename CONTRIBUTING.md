# Contributing to NXT-CAM

Thank you for your interest. Issues and pull requests should preserve NXT-CAM's two core
principles: visually intentional development and fidelity to the photographed content.

## Before you begin

- search existing issues for the same proposal or problem;
- include the device, Android version and reproducible steps;
- never attach API keys, unreviewed logs or private photographs;
- report vulnerabilities through the channel described in [SECURITY.md](SECURITY.md), not in a
  public issue.

## Code changes

1. Create a fork and a focused branch.
2. Preserve the BYOK architecture and never add credentials to source code.
3. Keep the constraints that prevent invented or altered scene content.
4. Consider orientation, foldable layouts and differences between CameraX implementations.
5. Run the checks before opening a pull request:

   ```bash
   ./gradlew assembleDebug lintDebug
   ```

Describe what changed, how it was tested and which devices were used.
