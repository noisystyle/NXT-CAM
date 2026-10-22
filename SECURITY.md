# Security

## Reporting a vulnerability

Do not disclose vulnerabilities or credentials in a public issue. Use **Report a vulnerability**
in the repository's Security tab and include the impact, affected version and minimal reproduction
steps.

## API keys

NXT-CAM uses a BYOK model. Keys are entered at runtime and encrypted on-device with Android
Keystore, but a client device is not equivalent to a controlled backend.

Use a dedicated, revocable key, configure provider-side budgets and alerts, and never share
screenshots, logs or builds that may contain credentials. If in doubt, revoke the key immediately
from the provider dashboard.
