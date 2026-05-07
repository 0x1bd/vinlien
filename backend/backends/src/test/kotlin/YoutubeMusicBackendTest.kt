import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.kvxd.vinlien.backends.youtube.buildYoutubeMusicSearchRequestBody
import org.kvxd.vinlien.backends.youtube.parseYoutubeMusicSearchResults

class YoutubeMusicBackendTest {
    @Test
    fun `search request includes required client fields`() {
        val body = Json.parseToJsonElement(
            buildYoutubeMusicSearchRequestBody(
                query = "PR1SVX Crystals",
                params = "EgWKAQIIAWoMEA4QChADEAQQCRAF",
                clientVersionDate = "20260507"
            )
        )

        val root = body.jsonObject
        val client = root["context"]!!.jsonObject["client"]!!.jsonObject

        assertEquals("WEB_REMIX", client["clientName"]!!.jsonPrimitive.content)
        assertEquals("1.20260507.01.00", client["clientVersion"]!!.jsonPrimitive.content)
        assertEquals("en", client["hl"]!!.jsonPrimitive.content)
        assertEquals("US", client["gl"]!!.jsonPrimitive.content)
        assertEquals(0, root["context"]!!.jsonObject["user"]!!.jsonObject.size)
        assertEquals("PR1SVX Crystals", root["query"]!!.jsonPrimitive.content)
        assertEquals("EgWKAQIIAWoMEA4QChADEAQQCRAF", root["params"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parses song result from YouTube Music renderer`() {
        val json = Json.parseToJsonElement(
            """
            {
              "contents": {
                "tabbedSearchResultsRenderer": {
                  "tabs": [{
                    "tabRenderer": {
                      "content": {
                        "sectionListRenderer": {
                          "contents": [{
                            "musicShelfRenderer": {
                              "contents": [{
                                "musicResponsiveListItemRenderer": {
                                  "thumbnail": {
                                    "musicThumbnailRenderer": {
                                      "thumbnail": {
                                        "thumbnails": [{ "url": "https://example.test/art.jpg" }]
                                      }
                                    }
                                  },
                                  "flexColumns": [{
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                      "text": {
                                        "runs": [{
                                          "text": "Crystals",
                                          "navigationEndpoint": {
                                            "watchEndpoint": {
                                              "videoId": "0MP3eBOoZj0",
                                              "watchEndpointMusicSupportedConfigs": {
                                                "watchEndpointMusicConfig": {
                                                  "musicVideoType": "MUSIC_VIDEO_TYPE_ATV"
                                                }
                                              }
                                            }
                                          }
                                        }]
                                      }
                                    }
                                  }, {
                                    "musicResponsiveListItemFlexColumnRenderer": {
                                      "text": {
                                        "runs": [
                                          { "text": "Song" },
                                          { "text": " • " },
                                          {
                                            "text": "PR1SVX",
                                            "navigationEndpoint": {
                                              "browseEndpoint": { "browseId": "UCartist" }
                                            }
                                          },
                                          { "text": " • " },
                                          {
                                            "text": "Crystals",
                                            "navigationEndpoint": {
                                              "browseEndpoint": { "browseId": "MPREalbum" }
                                            }
                                          },
                                          { "text": " • " },
                                          { "text": "1:03" }
                                        ]
                                      }
                                    }
                                  }],
                                  "playlistItemData": { "videoId": "0MP3eBOoZj0" },
                                  "overlay": {
                                    "musicItemThumbnailOverlayRenderer": {
                                      "content": {
                                        "musicPlayButtonRenderer": {
                                          "playNavigationEndpoint": {
                                            "watchEndpoint": {
                                              "videoId": "0MP3eBOoZj0",
                                              "watchEndpointMusicSupportedConfigs": {
                                                "watchEndpointMusicConfig": {
                                                  "musicVideoType": "MUSIC_VIDEO_TYPE_ATV"
                                                }
                                              }
                                            }
                                          }
                                        }
                                      }
                                    }
                                  }
                                }
                              }]
                            }
                          }]
                        }
                      }
                    }
                  }]
                }
              }
            }
            """.trimIndent()
        )

        val results = parseYoutubeMusicSearchResults(json)

        assertEquals(1, results.size)
        assertEquals("0MP3eBOoZj0", results.first().videoId)
        assertEquals("Crystals", results.first().title)
        assertEquals(listOf("PR1SVX"), results.first().artists)
        assertEquals("Crystals", results.first().albumTitle)
        assertEquals(63_000L, results.first().durationMs)
        assertEquals("MUSIC_VIDEO_TYPE_ATV", results.first().videoType)
    }
}
