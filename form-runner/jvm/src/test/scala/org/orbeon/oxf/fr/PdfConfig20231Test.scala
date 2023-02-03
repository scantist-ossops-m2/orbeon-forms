package org.orbeon.oxf.fr

import org.orbeon.oxf.fr.pdf.PdfConfig20231
import org.orbeon.oxf.fr.pdf.definitions20231._
import org.orbeon.oxf.test.XMLSupport
import org.orbeon.oxf.xml.IntelliJ.tinyTreeToPrettyString
import org.orbeon.saxon.om.NodeInfo
import org.orbeon.scaxon.NodeConversions._
import org.scalatest.funspec.AnyFunSpec


class PdfConfig20231Test extends AnyFunSpec with XMLSupport {

  describe("PDF header/footer configuration conversions between ADT/JSON/XML") {

    val defaultOrbeonFormsConfigAdt: PdfConfig20231.MyState =
      FormRunnerPdfConfigRoot(
        Map(
          HeaderFooterPageType.All -> Map(
            HeaderFooterType.Header -> Map(
              HeaderFooterPosition.Left -> PdfHeaderFooterCellConfig.Template(
                Map(
                  "_" -> "{$fr-logo}"
                ),
                Some("not(xxf:get-request-parameter('show-logo') = 'false')")
              ),
              HeaderFooterPosition.Center -> PdfHeaderFooterCellConfig.Template(
                Map(
                  "_" -> "{$fr-form-title}"
                ),
                None
              )
            ),
            HeaderFooterType.Footer -> Map(
              HeaderFooterPosition.Left -> PdfHeaderFooterCellConfig.Template(
                Map(
                  "_" -> "{$fr-form-title}"
                ),
                None
              ),
              HeaderFooterPosition.Center -> PdfHeaderFooterCellConfig.Template(
                Map(
                  "en" -> "Page {$fr-page-number} of {$fr-page-count}",
                  "fr" -> "Page {$fr-page-number} sur {$fr-page-count}"
                ),
                None
              ),
              HeaderFooterPosition.Right -> PdfHeaderFooterCellConfig.None
            )
          )
        ),
        List(
          Param.ImageParam("fr-logo", None),
          Param.FormTitleParam("fr-form-title"),
          Param.PageNumberParam("fr-page-number", Some(CounterFormat.LowerRoman)),
          Param.PageCountParam("fr-page-count", Some(CounterFormat.LowerRoman))
        )
      )

    val defaultOrbeonFormsConfigXml: NodeInfo =
      <json type="object">
          <pages type="object">
              <all type="object">
                  <header type="object">
                      <left type="object">
                          <values type="object">
                              <_>{{$fr-logo}}</_>
                          </values>
                          <visible>not(xxf:get-request-parameter('show-logo') = 'false')</visible>
                      </left>
                      <center type="object">
                          <values type="object">
                              <_>{{$fr-form-title}}</_>
                          </values>
                      </center>
                  </header>
                  <footer type="object">
                      <left type="object">
                          <values type="object">
                              <_>{{$fr-form-title}}</_>
                          </values>
                      </left>
                      <center type="object">
                          <values type="object">
                              <en>Page {{$fr-page-number}} of {{$fr-page-count}}</en>
                              <fr>Page {{$fr-page-number}} sur {{$fr-page-count}}</fr>
                          </values>
                      </center>
                      <right>none</right>
                  </footer>
              </all>
          </pages>
          <parameters type="array">
              <_ type="ImageParam">
                  <name>fr-logo</name>
                  <url type="null"/>
              </_>
              <_ type="FormTitleParam">
                  <name>fr-form-title</name>
              </_>
              <_ type="PageNumberParam">
                  <name>fr-page-number</name>
                  <format>lower-roman</format>
              </_>
              <_ type="PageCountParam">
                  <name>fr-page-count</name>
                  <format>lower-roman</format>
              </_>
          </parameters>
      </json>

    val defaultOrbeonFormsConfigJson =
      """{
        |  "pages": {
        |    "all": {
        |      "header": {
        |        "left": {
        |          "values": {
        |            "_": "{$fr-logo}"
        |          },
        |          "visible": "not(xxf:get-request-parameter('show-logo') = 'false')"
        |        },
        |        "center": {
        |          "values": {
        |            "_": "{$fr-form-title}"
        |          }
        |        }
        |      },
        |      "footer": {
        |        "left": {
        |          "values": {
        |            "_": "{$fr-form-title}"
        |          }
        |        },
        |        "center": {
        |          "values": {
        |            "en": "Page {$fr-page-number} of {$fr-page-count}",
        |            "fr": "Page {$fr-page-number} sur {$fr-page-count}"
        |          }
        |        },
        |        "right": "none"
        |      }
        |    }
        |  },
        |  "parameters": [
        |    {
        |      "ImageParam": {
        |        "name": "fr-logo"
        |      }
        |    },
        |    {
        |      "FormTitleParam": {
        |        "name": "fr-form-title"
        |      }
        |    },
        |    {
        |      "PageNumberParam": {
        |        "name": "fr-page-number",
        |        "format": "lower-roman"
        |      }
        |    },
        |    {
        |      "PageCountParam": {
        |        "name": "fr-page-count",
        |        "format": "lower-roman"
        |      }
        |    }
        |  ]
        |}""".stripMargin

    val customConfigAdt: PdfConfig20231.MyState =
      FormRunnerPdfConfigRoot(
        Map(
          HeaderFooterPageType.All -> Map(
            HeaderFooterType.Footer -> Map(
              HeaderFooterPosition.Right -> PdfHeaderFooterCellConfig.Template(
                Map(
                  "en" -> "Submitted on: {$current-dateTime}",
                  "fr" -> "Envoyé le: {$current-dateTime}"
                ),
                None
              )
            )
          )
        ),
        List(
          Param.ExpressionParam(
            "current-dateTime",
            "format-dateTime(current-dateTime(), '[D]/[M]/[Y] [h]:[m]:[s] [P,*-2]', xxf:lang(), (), ())"
          )
        )
      )

    val customConfigXml: NodeInfo =
      <json type="object">
          <pages type="object">
              <all type="object">
                  <footer type="object">
                      <right type="object">
                          <values type="object">
                              <en>Submitted on: {{$current-dateTime}}</en>
                              <fr>Envoyé le: {{$current-dateTime}}</fr>
                          </values>
                      </right>
                  </footer>
              </all>
          </pages>
          <parameters type="array">
              <_ type="ExpressionParam">
                  <name>current-dateTime</name>
                  <expr>format-dateTime(current-dateTime(), '[D]/[M]/[Y] [h]:[m]:[s] [P,*-2]', xxf:lang(), (), ())</expr>
              </_>
          </parameters>
      </json>

    val customConfigJson =
      """{
        |  "pages": {
        |    "all": {
        |      "footer": {
        |        "right": {
        |          "values": {
        |            "en": "Submitted on: {$current-dateTime}",
        |            "fr": "Envoyé le: {$current-dateTime}"
        |          }
        |        }
        |      }
        |    }
        |  },
        |  "parameters": [
        |    {
        |      "ExpressionParam": {
        |        "name": "current-dateTime",
        |        "expr": "format-dateTime(current-dateTime(), '[D]/[M]/[Y] [h]:[m]:[s] [P,*-2]', xxf:lang(), (), ())"
        |      }
        |    }
        |  ]
        |}""".stripMargin

    val mergedJson =
      """{
        |  "pages": {
        |    "all": {
        |      "header": {
        |        "left": {
        |          "values": {
        |            "_": "{$fr-logo}"
        |          },
        |          "visible": "not(xxf:get-request-parameter('show-logo') = 'false')"
        |        },
        |        "center": {
        |          "values": {
        |            "_": "{$fr-form-title}"
        |          }
        |        }
        |      },
        |      "footer": {
        |        "left": {
        |          "values": {
        |            "_": "{$fr-form-title}"
        |          }
        |        },
        |        "center": {
        |          "values": {
        |            "en": "Page {$fr-page-number} of {$fr-page-count}",
        |            "fr": "Page {$fr-page-number} sur {$fr-page-count}"
        |          }
        |        },
        |        "right": {
        |          "values": {
        |            "en": "Submitted on: {$current-dateTime}",
        |            "fr": "Envoyé le: {$current-dateTime}"
        |          }
        |        }
        |      }
        |    }
        |  },
        |  "parameters": [
        |    {
        |      "ImageParam": {
        |        "name": "fr-logo"
        |      }
        |    },
        |    {
        |      "FormTitleParam": {
        |        "name": "fr-form-title"
        |      }
        |    },
        |    {
        |      "PageNumberParam": {
        |        "name": "fr-page-number",
        |        "format": "lower-roman"
        |      }
        |    },
        |    {
        |      "PageCountParam": {
        |        "name": "fr-page-count",
        |        "format": "lower-roman"
        |      }
        |    },
        |    {
        |      "ExpressionParam": {
        |        "name": "current-dateTime",
        |        "expr": "format-dateTime(current-dateTime(), '[D]/[M]/[Y] [h]:[m]:[s] [P,*-2]', xxf:lang(), (), ())"
        |      }
        |    }
        |  ]
        |}""".stripMargin

    val Expected: List[(String, PdfConfig20231.MyState, NodeInfo, String)] = List(
      ("default Orbeon Forms config", defaultOrbeonFormsConfigAdt, defaultOrbeonFormsConfigXml, defaultOrbeonFormsConfigJson),
      ("custom config",               customConfigAdt,             customConfigXml,             customConfigJson),
    )

    for ((name, adt, xml, json) <- Expected)
      it(s"must pass for $name") {

//        println(FormRunnerPdfConfig.decode(json))
//        println(FormRunnerPdfConfig.configToJsonStringIndented(adt))
//        println(tinyTreeToPrettyString(FormRunnerPdfConfig.configToXml(adt)))

        assertXMLDocumentsIgnoreNamespacesInScope(
          xml.getDocumentRoot,
          PdfConfig20231.configToXml(adt)
        )
        assert(PdfConfig20231.decode(json).get == adt)
      }

    it("must merge configs") {

      val merged =
        PdfConfig20231.merge(
          defaultOrbeonFormsConfigAdt,
          customConfigAdt
        )

//      println(FormRunnerPdfConfig.encode(merged))

      assert(PdfConfig20231.decode(mergedJson).get == merged)
    }
  }
}
