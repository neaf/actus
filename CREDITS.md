# Credits

## DrivenByMoss

This project draws directly on [DrivenByMoss](https://github.com/git-moss/DrivenByMoss)
by **Jürgen Moßgraber** ([mossgrabers.de](https://www.mossgrabers.de)), a controller
extension framework supporting a huge range of hardware for Bitwig Studio, including
the Ableton Push 1/2/3 controllers.

Actus isn't a clean-room reimplementation. Where DrivenByMoss's Push 2 code — its
view/mode architecture, button and encoder wiring, USB display/pad-color handling,
constants like vendor/product IDs and MIDI port names — is already a good solution to
a problem we also have, we adapt it directly rather than rewriting it differently just
to avoid the resemblance. That's why Actus is licensed under the same terms as
DrivenByMoss (LGPL-3.0, see [LICENSE](LICENSE)) instead of a separate permissive
license: it keeps reuse honest without needing to track which file came from where.

Many thanks to Jürgen for building and maintaining DrivenByMoss as open source — it's
the reference for anyone writing a Bitwig controller extension, and this project
wouldn't look the way it does without it.

## Bitwig

Built against the [Bitwig Extension API](https://maven.bitwig.com), published by
Bitwig GmbH.
