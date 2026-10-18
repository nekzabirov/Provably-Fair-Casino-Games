#!/usr/bin/env bash
# Builds the public snapshot of this repository and syncs it into <export-dir>.
#
#   scripts/publish-public.sh <export-dir>          build + check + sync only
#   scripts/publish-public.sh <export-dir> --push   also commit and push the export,
#                                                   then publish docs/wiki/ to the wiki
#
# Steps: `git archive HEAD` into a temp dir -> delete the paths listed in
# .public/exclude -> copy .public/overlay/ on top -> run the forbidden-pattern guard
# (patterns in .public/forbidden-patterns and .public/docs-only-patterns) -> rsync the
# result into <export-dir>, keeping its .git. Only committed content (HEAD) is published.
#
# Maintainer tooling: it runs in the upstream source repository, which holds the
# .public/ directory; that directory is not part of the published snapshot.
set -euo pipefail

PUBLIC_REPO=nekzabirov/provably-fair-games
AUTHOR_NAME="Nekbakht Zabirov"
AUTHOR_EMAIL="17720956+nekzabirov@users.noreply.github.com"

usage() {
  echo "usage: $0 <export-dir> [--push]" >&2
  exit 2
}

EXPORT_DIR=""
PUSH=false
for arg in "$@"; do
  case "$arg" in
    --push) PUSH=true ;;
    -h|--help) usage ;;
    -*) echo "unknown option: $arg" >&2; usage ;;
    *) [[ -z "$EXPORT_DIR" ]] || usage; EXPORT_DIR="$arg" ;;
  esac
done
[[ -n "$EXPORT_DIR" ]] || usage

REPO_ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
PUBLIC_DIR="$REPO_ROOT/.public"
EXCLUDE_FILE="$PUBLIC_DIR/exclude"
PATTERNS_FILE="$PUBLIC_DIR/forbidden-patterns"
DOCS_ONLY_FILE="$PUBLIC_DIR/docs-only-patterns"
OVERLAY_DIR="$PUBLIC_DIR/overlay"

for f in "$EXCLUDE_FILE" "$PATTERNS_FILE" "$DOCS_ONLY_FILE"; do
  [[ -f "$f" ]] || { echo "missing $f" >&2; exit 1; }
done

STAGE="$(mktemp -d "${TMPDIR:-/tmp}/publish-public.XXXXXX")"
WIKI_DIR=""
cleanup() {
  rm -rf "$STAGE"
  if [[ -n "$WIKI_DIR" ]]; then rm -rf "$WIKI_DIR"; fi
}
trap cleanup EXIT

echo "==> Exporting HEAD ($(git -C "$REPO_ROOT" rev-parse --short HEAD))"
git -C "$REPO_ROOT" archive --format=tar HEAD | tar -x -C "$STAGE"

echo "==> Removing excluded paths"
while IFS= read -r line || [[ -n "$line" ]]; do
  pattern="${line%%#*}"
  pattern="$(printf '%s' "$pattern" | sed -e 's/[[:space:]]*$//' -e 's/^[[:space:]]*//')"
  [[ -n "$pattern" ]] || continue
  pattern="${pattern%/}"
  pattern="${pattern#/}"
  if [[ "$pattern" == */* ]]; then
    find "$STAGE" -path "$STAGE/$pattern" -prune -exec rm -rf {} +
  else
    find "$STAGE" -name "$pattern" -prune -exec rm -rf {} +
  fi
done < "$EXCLUDE_FILE"

if [[ -d "$OVERLAY_DIR" ]]; then
  echo "==> Applying overlay"
  cp -R "$OVERLAY_DIR/." "$STAGE/"
fi

echo "==> Checking for forbidden patterns"
STAGE="$STAGE" PATTERNS_FILE="$PATTERNS_FILE" DOCS_ONLY_FILE="$DOCS_ONLY_FILE" perl -e '
  use strict;
  use warnings;
  use File::Find;

  my $root = $ENV{STAGE};
  sub read_patterns {
    my ($file) = @_;
    open my $fh, "<", $file or die "cannot read $file: $!";
    my @patterns;
    while (my $p = <$fh>) {
      chomp $p;
      $p =~ s/^\s+|\s+$//g;
      next if $p eq "" || $p =~ /^#/;
      push @patterns, $p;
    }
    close $fh;
    return @patterns;
  }
  sub combine {
    my $combined = join "|", map { "(?:$_)" } @_;
    return qr/$combined/i;
  }
  my $forbidden = combine(read_patterns($ENV{PATTERNS_FILE}));
  my @docs_only = read_patterns($ENV{DOCS_ONLY_FILE});
  my $docs_only = @docs_only ? combine(@docs_only) : undef;
  my $cyrillic = qr/\xD0[\x81\x90-\xBF]|\xD1[\x80-\x8F\x91]/;

  my $hits = 0;
  my $report = sub {
    my ($where, $what) = @_;
    print "$where: $what\n";
    $hits++;
  };

  find({ no_chdir => 1, wanted => sub {
    my $path = $File::Find::name;
    return if $path eq $root;
    my $rel = substr($path, length($root) + 1);
    if ($rel eq ".git" || $rel =~ m{^\.git/}) { $File::Find::prune = 1; return; }
    $report->($rel, "forbidden pattern in path") if $rel =~ $forbidden;
    $report->($rel, "Cyrillic characters in path") if $rel =~ $cyrillic;
    return unless -f $path;
    return if -B $path;
    my $docs = ($rel eq "README.md" || $rel =~ m{^docs/wiki/});
    open my $fh, "<:raw", $path or die "cannot read $rel: $!";
    while (my $line = <$fh>) {
      if ($line =~ $forbidden) { $report->("$rel:$.", "forbidden pattern \"$&\""); }
      if ($line =~ $cyrillic)  { $report->("$rel:$.", "Cyrillic characters"); }
      if (!$docs && defined $docs_only && $line =~ $docs_only) { $report->("$rel:$.", "\"$&\" is allowed only in README.md and docs/wiki"); }
    }
    close $fh;
  }}, $root);

  if ($hits) {
    print STDERR "Guard failed: $hits forbidden occurrence(s).\n";
    exit 1;
  }
  print "Guard passed.\n";
'

echo "==> Syncing into $EXPORT_DIR"
mkdir -p "$EXPORT_DIR"
rsync -a --delete --exclude .git "$STAGE/" "$EXPORT_DIR/"

if ! $PUSH; then
  echo "Done (no push)."
  exit 0
fi

DATE="$(date -u +%Y-%m-%d)"
commit_all() {
  local dir="$1" message="$2"
  git -C "$dir" add -A
  if git -C "$dir" diff --cached --quiet; then
    echo "Nothing to commit in $dir"
    return 1
  fi
  git -C "$dir" \
    -c user.name="$AUTHOR_NAME" -c user.email="$AUTHOR_EMAIL" \
    commit -q --author="$AUTHOR_NAME <$AUTHOR_EMAIL>" -m "$message"
}

echo "==> Committing and pushing $PUBLIC_REPO"
[[ -d "$EXPORT_DIR/.git" ]] || git -C "$EXPORT_DIR" init -q -b main
if ! git -C "$EXPORT_DIR" remote get-url origin >/dev/null 2>&1; then
  git -C "$EXPORT_DIR" remote add origin "git@github.com:${PUBLIC_REPO}.git"
fi
if commit_all "$EXPORT_DIR" "Sync with upstream ($DATE)"; then
  git -C "$EXPORT_DIR" push origin HEAD:main
fi

echo "==> Publishing docs/wiki to the GitHub Wiki"
WIKI_DIR="$(mktemp -d "${TMPDIR:-/tmp}/publish-wiki.XXXXXX")"
git clone -q "git@github.com:${PUBLIC_REPO}.wiki.git" "$WIKI_DIR"
find "$WIKI_DIR" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
cp -R "$EXPORT_DIR/docs/wiki/." "$WIKI_DIR/"
if commit_all "$WIKI_DIR" "Sync with upstream ($DATE)"; then
  git -C "$WIKI_DIR" push origin HEAD
fi

echo "Done."
