# Code signing policy

Free code signing provided by [SignPath.io](https://about.signpath.io), certificate by
[SignPath Foundation](https://signpath.org).

Windows installers of Synesis releases are signed. Only files built from this repository by its release workflow
(`.github/workflows/release.yml`), on GitHub-hosted runners, are submitted for signing, and every signing request is
approved by hand. Each release file also carries a GitHub build-provenance attestation:
`gh attestation verify <file> --repo Liparakis/Synesis`.

## Team roles

- **Authors** (may change the source without further review): [Liparakis](https://github.com/Liparakis)
- **Reviewers** (review every change from anyone else, such as a pull request): [Liparakis](https://github.com/Liparakis)
- **Approvers** (approve each signing request): [Liparakis](https://github.com/Liparakis)

All team members use multi-factor authentication for GitHub and SignPath.

## Privacy

Synesis sends no telemetry and no usage data. It connects to other networks only for what a person sets up:

- **Link** connects this computer to the other computers you invite or join, and sends them the project's
  coordination log and code, end to end encrypted.
- To find this computer's public address for Link, it sends one STUN request to `stun.cloudflare.com:3478`
  (`SYNESIS_STUN_SERVER` names another). That request carries no project data; see
  [Cloudflare's privacy policy](https://www.cloudflare.com/privacypolicy/).
- The agent tools you install and run (for example Codex or Claude Code) talk to their own providers under those
  providers' terms; Synesis does not send them anything of its own.

The installer changes the system only as it says: the application folder, the user PATH, the `synesis://` link
handler, and, if you tick it, a Windows Firewall rule. Uninstall removes all of them.
