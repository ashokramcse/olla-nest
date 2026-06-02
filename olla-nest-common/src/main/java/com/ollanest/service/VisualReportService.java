package com.ollanest.service;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Generates a self-contained, styled HTML report from deep research results.
 *
 * The report includes:
 * - Auto-generated table of contents from headings
 * - Hero section with gradient background
 * - Dark/light theme via prefers-color-scheme
 * - Collapsible sources list
 * - Print/share toolbar
 * - Inline rendered markdown body
 *
 * No external dependencies — all fonts/styles are inline.
 */
@Service
public class VisualReportService {

    private final Parser mdParser;
    private final HtmlRenderer mdRenderer;

    public VisualReportService() {
        MutableDataSet opts = new MutableDataSet();
        this.mdParser = Parser.builder(opts).build();
        this.mdRenderer = HtmlRenderer.builder(opts).build();
    }

    /**
     * Generate a full HTML research report.
     *
     * @param query    the original research query
     * @param markdown the markdown content of the report
     * @param sources  list of {title, url, snippet} source maps
     * @param durationMs how long the research took
     */
    public String generate(String query, String markdown, List<Map<String, Object>> sources, long durationMs) {
        String bodyHtml = mdRenderer.render(mdParser.parse(markdown != null ? markdown : ""));
        String sourcesHtml = buildSourcesHtml(sources);
        String toc = buildToc(markdown);
        double durationS = durationMs / 1000.0;

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                  <title>%s — Olla Nest Research</title>
                  <style>
                    :root {
                      --ac: #F5C800; --ac-text: #1a1a1a; --ac-pale: #fef9e0;
                      --bg: #f7f6f1; --surface: #ffffff; --text: #1a1a1a;
                      --muted: #666; --border: #e5e3dc; --radius: 12px;
                      --font: 'Helvetica Neue', Arial, system-ui, sans-serif;
                      --mono: 'Menlo', 'Consolas', monospace;
                    }
                    @media (prefers-color-scheme: dark) {
                      :root {
                        --bg: #0f0f0f; --surface: #1a1a1a; --text: #ebebeb;
                        --muted: #aaa; --border: #333;
                      }
                    }
                    *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
                    body { font-family: var(--font); background: var(--bg); color: var(--text); line-height: 1.6; }
                    .hero {
                      background: linear-gradient(135deg, var(--ac) 0%%, #f5a800 50%%, #e68c00 100%%);
                      padding: 48px 32px 32px;
                      color: var(--ac-text);
                    }
                    .hero-label { font-size: 11px; text-transform: uppercase; letter-spacing: 0.1em; opacity: 0.7; margin-bottom: 12px; }
                    .hero-title { font-size: clamp(24px, 4vw, 40px); font-weight: 700; line-height: 1.2; margin-bottom: 16px; }
                    .hero-meta { font-size: 13px; opacity: 0.8; }
                    .toolbar {
                      background: var(--surface); border-bottom: 1px solid var(--border);
                      padding: 12px 32px; display: flex; gap: 12px; align-items: center;
                      position: sticky; top: 0; z-index: 100;
                    }
                    .toolbar-btn {
                      background: var(--bg); border: 1px solid var(--border); border-radius: 8px;
                      padding: 6px 14px; font-size: 13px; cursor: pointer; color: var(--text);
                    }
                    .toolbar-btn:hover { background: var(--ac-pale); border-color: var(--ac); }
                    .container { max-width: 860px; margin: 0 auto; padding: 32px 24px; }
                    .toc { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius); padding: 20px 24px; margin-bottom: 32px; }
                    .toc-title { font-size: 11px; text-transform: uppercase; letter-spacing: 0.1em; color: var(--muted); margin-bottom: 12px; font-weight: 600; }
                    .toc a { display: block; color: var(--text); text-decoration: none; padding: 3px 0; font-size: 14px; }
                    .toc a:hover { color: #a07000; }
                    .body-content h1, .body-content h2, .body-content h3 { margin: 28px 0 12px; line-height: 1.3; }
                    .body-content h1 { font-size: 26px; border-bottom: 2px solid var(--ac); padding-bottom: 8px; }
                    .body-content h2 { font-size: 21px; }
                    .body-content h3 { font-size: 17px; color: var(--muted); }
                    .body-content p { margin-bottom: 14px; }
                    .body-content ul, .body-content ol { margin: 0 0 14px 24px; }
                    .body-content li { margin-bottom: 4px; }
                    .body-content code { font-family: var(--mono); background: var(--border); padding: 2px 6px; border-radius: 4px; font-size: 0.9em; }
                    .body-content pre { background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 16px; overflow-x: auto; margin-bottom: 14px; }
                    .body-content blockquote { border-left: 3px solid var(--ac); padding-left: 16px; color: var(--muted); margin-bottom: 14px; }
                    .sources { margin-top: 40px; }
                    .sources-header { font-size: 14px; font-weight: 600; margin-bottom: 12px; cursor: pointer; display: flex; align-items: center; gap: 8px; }
                    .sources-list { display: flex; flex-direction: column; gap: 8px; }
                    .source-item { background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 12px 16px; }
                    .source-title { font-size: 13px; font-weight: 500; }
                    .source-url { font-size: 11px; color: var(--muted); word-break: break-all; }
                    .footer { text-align: center; padding: 32px; font-size: 12px; color: var(--muted); }
                    @media print { .toolbar { display: none; } }
                  </style>
                </head>
                <body>

                <div class="hero">
                  <div class="hero-label">// Olla Nest Deep Research</div>
                  <div class="hero-title">%s</div>
                  <div class="hero-meta">%s &nbsp;·&nbsp; %.1f seconds &nbsp;·&nbsp; %d sources</div>
                </div>

                <div class="toolbar">
                  <button class="toolbar-btn" onclick="window.print()">🖨️ Print</button>
                  <button class="toolbar-btn" onclick="navigator.clipboard.writeText(document.body.innerText)">📋 Copy text</button>
                  <span style="margin-left:auto;font-size:12px;opacity:0.5">Olla Nest Research Report</span>
                </div>

                <div class="container">
                  %s
                  <div class="body-content">%s</div>
                  <div class="sources">
                    <div class="sources-header" onclick="this.nextElementSibling.style.display=this.nextElementSibling.style.display==='none'?'flex':'none'">
                      📚 Sources (%d)
                    </div>
                    <div class="sources-list">%s</div>
                  </div>
                </div>

                <div class="footer">Generated by Olla Nest &nbsp;·&nbsp; %s</div>
                </body>
                </html>
                """.formatted(
                escape(query),
                escape(query),
                LocalDate.now().toString(),
                durationS,
                sources != null ? sources.size() : 0,
                toc.isBlank() ? "" : "<div class=\"toc\"><div class=\"toc-title\">Table of Contents</div>" + toc + "</div>",
                bodyHtml,
                sources != null ? sources.size() : 0,
                sourcesHtml,
                LocalDate.now().toString()
        );
    }

    private String buildToc(String markdown) {
        if (markdown == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : markdown.split("\n")) {
            if (line.startsWith("## ")) {
                String heading = line.substring(3).trim();
                String anchor = heading.toLowerCase().replaceAll("[^a-z0-9]+", "-");
                sb.append("<a href=\"#").append(anchor).append("\">").append(escape(heading)).append("</a>");
            } else if (line.startsWith("# ")) {
                String heading = line.substring(2).trim();
                String anchor = heading.toLowerCase().replaceAll("[^a-z0-9]+", "-");
                sb.append("<a href=\"#").append(anchor).append("\" style=\"font-weight:600\">").append(escape(heading)).append("</a>");
            }
        }
        return sb.toString();
    }

    private String buildSourcesHtml(List<Map<String, Object>> sources) {
        if (sources == null || sources.isEmpty()) return "<p style=\"color:var(--muted)\">No sources recorded.</p>";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> src : sources) {
            String title = (String) src.getOrDefault("title", "Source");
            String url = (String) src.getOrDefault("url", "#");
            sb.append("<div class=\"source-item\">")
              .append("<div class=\"source-title\"><a href=\"").append(escape(url)).append("\" target=\"_blank\" rel=\"noopener\">")
              .append(escape(title)).append("</a></div>")
              .append("<div class=\"source-url\">").append(escape(url)).append("</div>")
              .append("</div>");
        }
        return sb.toString();
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
