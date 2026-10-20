# README screenshots

These screenshots show NetLog inspecting four real requests from the bundled `io.github.kevinmf1.netlog.sample` app on a separate emulator. They contain no internal app traffic, company endpoints, customer data, or real credentials.

The sample calls its own loopback HTTP server. Its product data and `sample-secret` token are dummy values defined in [MainActivity.java](../../sample/src/main/java/io/github/kevinmf1/netlog/sample/MainActivity.java).

The project owner captured these images directly from the running desktop app on 20 October 2026. The PNGs are copied without visual edits.

| Image | State |
| --- | --- |
| `response.png` | Four requests; POST cart selected; Response open; secrets hidden |
| `overview.png` | POST cart selected; Overview open; URL, status, and timing visible |
| `request-hidden.png` | POST cart selected; Request open; secrets hidden |
| `request-revealed.png` | Same request; dummy authorization token revealed |

To capture new images, run the [sample app](../../README.md#try-it-with-the-sample-app), filter NetLog to that source, and reproduce these states. Use sample traffic only, and check the entire frame before replacing an image.
