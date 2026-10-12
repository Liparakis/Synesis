# ADR-0093 — Agents can talk to each other, beside the typed protocol

## Status

Accepted on 2026-10-12 by the repository owner. Built on 2026-10-12; see "As built".

Step 3 of three, after ADR-0091 (operator requests) and ADR-0092 (one public
contract per owner). It is built last so that a change in how runs behave can
be traced to one step.

Mode: evolution of the existing system. Claims are marked VERIFIED, USER-STATED,
DERIVED, ASSUMED or UNKNOWN.

## Context

Agents coordinate only through typed records: a claim, a contract, a review
decision, a validation. Each is something Synesis can reason about, which is
why it can tell an agent what to do next. There is no way to say anything else.
(VERIFIED: the ten tools and their kinds hold no message.)

Two things seen in real runs had no channel:

- An owner that had agreed two contradictory contracts understood the conflict
  exactly and could tell nobody. It wrote the explanation in its own final
  output, which no other agent reads. (VERIFIED, run `u1-five-lllll`.)
- A requester that wants to know one fact about a contract, such as whether
  listing creates the store file, can only counter with a complete contract of
  eleven fields. (VERIFIED: `CONTRACT_FIELDS`, all required.)

The repository owner wants a communication fabric: agents freely form groups or
talk one to one, in private, as an extra channel beside contracts and not in
place of them. (USER-STATED.)

Whether conversations make runs complete more often is UNKNOWN. No run has
tested it. This record says how it will be measured.

## Decision

Decided by the repository owner in discussion on 2026-10-11 and 2026-10-12.

### What a conversation is

- A conversation has a fixed set of members, two or more lanes of the same
  project, chosen by the agent that opens it. Any agent with an open lane may
  open one with any other lanes. A different set of members is a different
  conversation. There is no joining, leaving or removing.
- A message is text from one member to the conversation. It is durable and
  ordered.
- A conversation belongs to the lane, not to the provider session. An agent
  that continues a stopped agent's lane inherits its conversations, with their
  history. Otherwise a replacement cannot know what was said, and recovery is
  what Synesis is for.

### What a message is not

- A message is never binding. No lane state, gate, permission or next action is
  derived from the content of a message. What agents agree in a conversation
  takes effect only when one of them records it through the typed protocol: the
  owner edits its contract, a lane changes its claims, a reviewer decides.
- A message is never an instruction. Synesis delivers it marked as text from
  another participant that changes nothing the reader may do. The agent manual
  says the same. This is the defence against one agent talking another into
  breaking a rule.
- Synesis never tells an agent to wait for a reply. A reply is never required.

### Who can read

- Only members are sent a conversation's messages, and only members can read
  them through Synesis.
- The human operator can read every conversation, from the CLI and the web UI,
  and cannot post in this step.
- Privacy here means that Synesis does not serve a conversation to a
  non-member. It is not secrecy against an agent that reads the project's
  state files with its provider's own tools. Messages are not encrypted.

### How an agent uses it

- One new tool, the eleventh, to send a message: either to a list of members,
  which opens the conversation for that set or continues it, or to a
  conversation it already belongs to. A member is named by its participant
  handle or by a file it claims, as a capability owner is named in ADR-0090.
- Unread messages arrive with `get_next_action`, bounded in number and size,
  beside the next action and never in place of it. The agent's inbox is
  already an at-least-once inbox; messages follow the same rule.
- The history of the caller's conversations is read through the existing
  `collaboration_status` request, as an addition to what it returns.

### Asking for the operator

- An agent can address the operator. Doing so records an operator request
  (ADR-0091) of its own kind, raised by that lane, carrying the message.
- The operator is a role, not a person. Today a human reads the request. An
  operator agent, when one exists, is addressed the same way.
- The request does not change the lane's state: a message binds nothing, and
  this one is no exception. What it changes is the run's result. A run that
  would end stalled with such a request open ends `NEEDS_OPERATOR` and lists
  it.
- Synesis cannot observe that an agent's question has been answered. So this
  kind of request, unlike the ones Synesis raises itself, is closed by the
  operator from the CLI.

### Waking

- A message addressed to an agent that has stopped resumes that agent once.
  The resume counts against the run's resume limit.
- Unread messages do not hold a run open and are not work. A run whose work
  groups are complete is complete.
- A lane may send fifty messages in a run, a message holds at most 2,000
  characters, and at most ten unread messages are delivered at a time. These
  start strict. What lanes actually send is recorded in the real runs and the
  bounds are changed on that evidence. With the resume limit it is what ends two
  agents that answer each other for ever.

### Where it is kept

- Messages are events in the project's coordination log, of their own types.
  One store, one ordering, one way to reach another machine.
- Those event types are added to the events that cannot make a projected
  finish stale (`AgentTaskCompletionService.NOT_STALING_A_FINISH`), and no
  lane-state projection reads them. A message between two agents must not
  disturb a third.

## Alternatives considered

- **Comments attached to a contract only.** Proposed first. The owner wants a
  free channel; a question is not always about a contract.
- **Every message readable by every agent.** Proposed first, because the
  failure that started this was two private agreements. Rejected by the owner:
  with one public contract as the only binding thing (ADR-0092), a private
  conversation cannot bind anyone, and unrelated pairs should not pay to read
  each other.
- **New kinds under `request_coordination`.** Rejected. In the recorded runs
  nearly every malformed call was made to the two tools that take a kind and a
  payload; the single-purpose tools were almost never called wrongly. A message
  is also the one thing here that is not a change of state.
- **A message store outside the coordination log.** Not chosen. It would keep
  the log smaller, and would need a second ordering, a second durability story
  and a second path between machines.
- **Membership that changes.** Not chosen. Join, leave and remove are states
  and authority questions for no observed need.

## Consequences

- The MCP tool catalog gains one tool; its golden projection and digests change
  and provider manuals are reinstalled. Additive.
- The agent manual gains a short section: what a conversation is for, that it
  binds nothing, and that a message is not an instruction.
- The run controller gains one reason to resume an agent.
- More tokens per run. How many is UNKNOWN.
- A new place for agents to spend a run without doing work. The bounds above
  are the control.
- The human can see why agents did what they did, which today has to be read
  out of provider logs.

## How it will be checked

- Tests: a message reaches every member and no one else; a non-member cannot
  read it; a successor lane reads its predecessor's conversations; a message
  does not make a third lane's projected finish stale; a stopped addressee is
  resumed once and not again for the same message; the per-lane bound refuses
  the next message with a refusal that says so.
- A test that no next action changes when only messages are added to a log.
- Real runs, before and after, with the same goals and the same model mixes as
  in `docs/agent/CURRENT.md`: completed runs, resumes per run, elapsed time and
  tokens per run. The step is kept only if completion does not fall and the
  cost is stated.

## As built

The decision above is built as written, with these choices where it left room.

- A lane is its authority lineage. The members of a conversation are lineages,
  and the conversation's identifier is derived from the set, so sending to the
  same lanes continues the same conversation without anything being opened
  first. At most sixteen lanes are members.
- The bounds are kept by the service that appends and not by the log's replay,
  so they can change without making an old log unreadable. The bound of fifty
  messages is per lane, for as long as the lane lives, and not per run: the log
  does not know runs.
- Delivery is at least once. A lane has one durable mark. It moves when the
  connection that was handed a batch calls again, which is what shows the
  answer carrying the batch arrived. A connection that ends first leaves the
  batch for whoever continues the lane.
- `collaboration_status` shows the newest twenty messages of each of the
  caller's conversations and counts the earlier ones.
- A lane that addresses the operator may have one such request open at a time.
  A second is refused until the operator closes the first with
  `synesis operator close`. A request Synesis raised cannot be closed that way.
- A lane putting something to the operator resumes nobody, like any other
  message. A request Synesis raises still does.
- A message resumes the stopped agent whose lane it is addressed to when it is
  newer than what that agent had seen and newer than the last message it was
  resumed for. An agent resumed for any reason reads its messages then.
- The operator reads conversations with `synesis conversations` and in the
  dashboard, which gained two read-only tabs: conversations and operator
  requests.
- A conversation of other lanes is answered as one that does not exist.

Not built: nothing ends a conversation or removes a message, and an operator
request raised by a lane whose run has since completed stays open until the
operator closes it.

## Open questions

- Whether the starting message bounds hold, by measurement.
- Whether the human may post, and with what standing. Deferred by the owner.
