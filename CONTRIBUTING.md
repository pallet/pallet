## Building

To build maven based pallet projects, you will need to have various repositories
in your `settings.xml`. One way to do this is to use the `settings.xml` in the
[pallet-settings-xml](https://github.com/pallet/pallet-settings-xml) project.

## Tests

Please make sure tests pass, and test cases are added to cover new code.

To run the pallet tests, you will need to authorise your id_rsa key on
localhost.

To run pallet-crates live-test, you can use the `live-test` profile:

    mvn clojure:test -P live-test -Dpallet.test.service-name=ec2

## Source Code Format

Source code should follow the following rules:
- No Tabs
- 80 character maximum line length

## Commit messages

Commit messages are used to generate the changelog (see below).

your messages should start with a single line that's no more than about 50
characters and that describes the changeset concisely, followed by a blank line,
followed by a more detailed explanation.

See [progit](http://progit.org/book/ch5-2.html#commit_guidelines) for a more
complete explaination of commit messages.

## Release Notes

The
[release notes](https://github.com/pallet/pallet/blob/support/0.7.x/ReleaseNotes.md)
are built using commit messages.

For the release notes format to be produced directly by git, just add the
following to your `.gitconfig` file under the `[pretty]` section:

    changelog = format:- %w(76,0,2)%s%n%w(76,2,2)%b

To generate the raw input for the changelog, then run the following, replacing
`pallet-0.4.0` with a commit or tag that you want to start from:

    git log --pretty=changelog  pallet-0.4.0..
