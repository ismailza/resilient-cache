/**
 * Renders release notes as Markdown from the Conventional Commits in a range.
 *
 *   node .github/scripts/release-notes.mjs --range=v0.1.0..HEAD [--version=0.2.0]
 *
 * With --version, the notes end with the dependency to add for that version.
 *
 * Only the user-visible types reach the notes (feat, fix), plus anything flagged breaking with
 * `!`. Everything else — chore, refactor, docs, test … — is deliberately
 * dropped: release notes are read by people deciding whether to care about a
 * deploy, not an audit log of the branch.
 */
import { execFileSync } from 'node:child_process';

const [, , ...args] = process.argv;

const options = parseArgs(args);
const range = options.range;

if (!range) {
  throw new Error(
    'Missing required --range argument (e.g. --range=v0.1.0..HEAD).',
  );
}

const SECTIONS = [
  { title: '⚠️ Breaking Changes', matches: (commit) => commit.breaking },
  {
    title: '✨ New Features',
    matches: (commit) => !commit.breaking && commit.type === 'feat',
  },
  {
    title: '🐛 Bug Fixes',
    matches: (commit) => !commit.breaking && commit.type === 'fix',
  },
];

const SUBJECT =
  /^(?<type>[a-z]+)(?:\((?<scope>[^)]+)\))?(?<breaking>!)?:\s*(?<description>.+)$/;

process.stdout.write(
  render(readCommits(range)) + (options.version ? installation(options.version) : ''),
);

function render(commits) {
  const lines = [];

  for (const section of SECTIONS) {
    const matching = commits.filter(section.matches);
    if (matching.length === 0) {
      continue;
    }

    lines.push(`### ${section.title}`, '');
    for (const commit of matching) {
      lines.push(`- ${renderCommit(commit)}`);
    }
    lines.push('');
  }

  if (lines.length === 0) {
    lines.push('_No user-visible changes since the last release._', '');
  }

  return lines.join('\n');
}

function installation(version) {
  return [
    '',
    '### 📦 Installation',
    '',
    'Published to Maven Central and GitHub Packages:',
    '',
    '```xml',
    '<dependency>',
    '  <groupId>io.github.ismailza</groupId>',
    '  <artifactId>resilient-cache-spring-boot-starter</artifactId>',
    `  <version>${version}</version>`,
    '</dependency>',
    '```',
    '',
  ].join('\n');
}

function renderCommit({ scope, description, sha }) {
  const prefix = scope ? `**${scope}:** ` : '';
  // A bare short SHA auto-links to its commit when rendered in this repo.
  return `${prefix}${description} (${sha.slice(0, 7)})`;
}

function readCommits(range) {
  const log = execFileSync(
    'git',
    ['log', '--no-merges', '--format=%H %s', range],
    { encoding: 'utf8' },
  );

  return log
    .split('\n')
    .filter(Boolean)
    .map((line) => {
      const separator = line.indexOf(' ');
      const sha = line.slice(0, separator);
      const groups = line.slice(separator + 1).match(SUBJECT)?.groups;

      // A subject that is not a Conventional Commit matches no section and is
      // dropped, exactly like an unlisted type.
      return {
        sha,
        type: groups?.type,
        scope: groups?.scope,
        breaking: Boolean(groups?.breaking),
        description: groups?.description,
      };
    });
}

function parseArgs(argv) {
  return argv.reduce((acc, arg) => {
    const match = arg.match(/^--([^=]+)=(.*)$/);
    if (match) {
      acc[match[1]] = match[2];
    }
    return acc;
  }, {});
}
