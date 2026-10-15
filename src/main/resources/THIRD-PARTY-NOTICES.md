# Third-party components

Music's own source is MIT licensed (the `LICENSE` file at the root of the jar and of the source
repository). Minecraft is not included in the mod and remains subject to Mojang/Microsoft's own
terms. This is not an official Minecraft product and is not approved by or associated with Mojang
or Microsoft.

| Component | Version | Upstream / license |
|---|---|---|
| Fabric API modules | from 0.141.6+1.21.11 | [FabricMC](https://github.com/FabricMC/fabric), Apache-2.0; nested jars with their own license files: fabric-api-base, fabric-lifecycle-events-v1, fabric-key-binding-api-v1, fabric-message-api-v1, fabric-networking-api-v1, fabric-rendering-v1, fabric-item-group-api-v1, fabric-object-builder-api-v1, fabric-registry-sync-v0, fabric-resource-loader-v1, fabric-transitive-access-wideners-v1 |
| JavaMP3 | 1.0.1 | [delthas/javamp3](https://github.com/delthas/javamp3), MIT — see below |
| Salamander Grand Piano V3 | 2017 release | Alexander Holm, [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) — see below |
| Libre Baskerville | glyphs baked into the piano texture | Impallari Type, SIL Open Font License 1.1 |
| Starter songs | six pieces in `data/music/starter_songs/` | public-domain compositions; notes from Mutopia Project editions placed in the Public Domain — see below |

## Piano samples

The grand piano sound uses recordings from **Salamander Grand Piano V3** by **Alexander Holm**
(a Yamaha C5), licensed under Creative Commons Attribution 3.0
(<https://creativecommons.org/licenses/by/3.0/>), obtained from
<https://archive.org/details/SalamanderGrandPianoV3>.

Changes made for Music: 30 notes in 5 of the 16 velocity layers, one microphone channel per note
energy-matched to the stereo pair, trimmed lengths with fades, string-resonance release samples
shortened, pedal noises shortened, re-encoded as mono Ogg Vorbis. The changed samples are under
`assets/music/instruments/grand_piano/` and remain under CC BY 3.0.

## Piano model

The Hurtorius Grand & Bench model, its textures and the "Hurtorius" wordmark are original work by
Hurtorius and are covered by Music's MIT license. The wordmark pixels were rasterised from
Libre Baskerville (SIL OFL 1.1); no font file is distributed.

## Starter songs

Every new world starts with six piano pieces, named here as the game lists them. The compositions
are in the public domain: their composers died between 1750 and 1925, and each was published before
1906. Their notes were copied from these editions of the
[Mutopia Project](https://www.mutopiaproject.org/), each placed in the Public Domain by its
typesetter:

| Piece | Mutopia edition | Source of the edition |
|---|---|---|
| J. S. Bach (Johann Sebastian Bach), Prelude in C, BWV 846 (the Prelude in C major) | Tobias Erbsland, <https://www.mutopiaproject.org/ftp/BachJS/BWV846/wtk1-prelude1/wtk1-prelude1.ly> | Das Wohltemperierte Clavier I |
| L. v. Beethoven (Ludwig van Beethoven), Für Elise, WoO 59 | Stelios Samelis, <https://www.mutopiaproject.org/ftp/BeethovenLv/WoO59/fur_Elise_WoO59/fur_Elise_WoO59.ly> | Breitkopf & Härtel, 1888 |
| E. Satie (Erik Satie), Gymnopédie No. 1 | Evin Robertson, <https://www.mutopiaproject.org/ftp/SatieE/gymnopedie_1/gymnopedie_1.ly> | Dover edition |
| F. Chopin (Frédéric Chopin), Prelude No. 4, E minor (Op. 28 No. 4) | Magnus Lewis-Smith, <https://www.mutopiaproject.org/ftp/ChopinFF/O28/Chop-28-4/Chop-28-4.ly> | Peters, 1879 |
| F. Chopin (Frédéric Chopin), Prelude No. 20, C minor (Op. 28 No. 20) | Magnus Lewis-Smith, <https://www.mutopiaproject.org/ftp/ChopinFF/O28/Chop-28-20/Chop-28-20.ly> | Edition Peters |
| S. Joplin (Scott Joplin), The Entertainer | Chris Sawer, revised by Simon Albrecht, <https://www.mutopiaproject.org/ftp/JoplinS/entertainer/entertainer.ly> | the original 1902 edition |

The MIDI files in the jar play the notes of these editions. How they are played (tempo, how loud
each piece and each hand is, a few dynamics, ritardandos and pedalling where the score leaves them to
the player) is Music's own and under its MIT license.

## JavaMP3

MP3 decoding uses JavaMP3 1.0.1 by delthas, nested as `META-INF/jars/javamp3-1.0.1.jar`: its classes
are unchanged, and Fabric Loom adds a generated `fabric.mod.json` so Fabric Loader can load it.

```
MIT License

Copyright (c) 2017 delthas

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
