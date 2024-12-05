package org.archive.resource.html;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.archive.extract.ExtractingResourceFactoryMapper;
import org.archive.extract.ExtractingResourceProducer;
import org.archive.extract.ProducerUtils;
import org.archive.extract.ResourceFactoryMapper;
import org.archive.resource.MetaData;
import org.archive.resource.Resource;
import org.archive.resource.ResourceConstants;
import org.archive.resource.ResourceParseException;
import org.archive.resource.ResourceProducer;
import org.htmlparser.nodes.TextNode;
import com.github.openjson.JSONArray;
import com.github.openjson.JSONException;
import com.github.openjson.JSONObject;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

import junit.framework.TestCase;

public class ExtractingParseObserverTest extends TestCase {

	private static final Logger LOG =
			Logger.getLogger(ExtractingParseObserverTest.class.getName());

	public void testHandleStyleNodeExceptions() throws Exception {
		String[] tests = {
				"some css",
				"url()",
				"url () ",
				"url ('')",
				"url (' ')",
				"url('\")",
				"url(')",
				"url('\"')",
				"url('\\\"\"')",
				"url(''''')"
		};
		boolean except = false;
		HTMLMetaData md = new HTMLMetaData(new MetaData());
		ExtractingParseObserver epo = new ExtractingParseObserver(md);
		for(String css : tests) {
			try {
				TextNode tn = new TextNode(css);
				epo.handleStyleNode(tn);
			} catch(Exception e) {
				System.err.format("And the winner is....(%s)\n", css);
				e.printStackTrace();
				except = true;
				throw e;
			}
			assertFalse(except);
		}
	}

	public void testHandleStyleNode() throws Exception {
		String[][] tests = {
				{""},
				{"url(foo.gif)","foo.gif"},
				{"url('foo.gif')","foo.gif"},
				{"url(\"foo.gif\")","foo.gif"},
				{"url(\\\"foo.gif\\\")","foo.gif"},
				{"url(\\'foo.gif\\')","foo.gif"},
				{"url(''foo.gif'')","foo.gif"},
				{"url(  foo.gif  )","foo.gif"},
				{"url('''')"},
				{"url('foo.gif'')","foo.gif"},
				};
		for(String[] testa : tests) {
			checkExtract(testa);
		}
	}

	/**
	 * Test whether the pattern matcher does extract nothing and also does not
	 * not hang-up if an overlong CSS link is truncated.
	 */
	public void testHandleStyleNodeNoHangupTruncated() throws Exception {
		StringBuilder sb = new StringBuilder();
		sb.append("url(");
		for (int i = 0; i < 500000; i++)
			sb.append('\'');
		sb.append("foo.gif");
		for (int i = 0; i < 499000; i++)
			sb.append('\'');
		String[] test = new String[1];
		test[0] = sb.toString();
		checkExtract(test);
	}

	/**
	 * Test whether the pattern matcher does not stack overflow with overlong
	 * sequence of quote characters around a CSS link.
	 */
	public void testHandleStyleNodeNoStackOverflow() throws Exception {
		StringBuilder sb = new StringBuilder();
		sb.append("url(");
		for (int i = 0; i < 20000; i++)
			sb.append('\'');
		sb.append("foos.gif");
		for (int i = 0; i < 20000; i++)
			sb.append('\'');
		sb.append(");");
		String[] test = new String[1];
		test[0] = sb.toString();
		checkExtract(test);
	}

	private void checkExtract(String[] data) throws JSONException {
//		System.err.format("CSS(%s) want[0](%s)\n",css,want[0]);
		String css = data[0];
		HTMLMetaData md = new HTMLMetaData(new MetaData());
		ExtractingParseObserver epo = new ExtractingParseObserver(md);
		try {
			TextNode tn = new TextNode(css);
			epo.handleStyleNode(tn);
		} catch(Exception e) {
			fail("Exception with CSS:" + css);
		}
		JSONArray a = md.optJSONArray("Links");
		if(data.length > 1) {
			assertNotNull(a);
			assertEquals(data.length-1,a.length());
			for(int i = 1; i < data.length; i++) {
				Object o = a.optJSONObject(i-1);
				
				assertTrue(o instanceof JSONObject);
				JSONObject jo = (JSONObject) o;
				assertEquals("CSS link extraction failed for <" + css + ">",
						data[i], jo.getString("href"));
			}
		} else {
			assertNull("Expected no extracted link for <" + css + ">", a);
		}
	}
	
	private void checkLink(Multimap<String,String> links, String url, String path) {
		assertTrue("Link with URL " + url + " not found in [" + String.join(", ", links.keySet()) + "]",
				links.containsKey(url));
		assertTrue("Wrong path " + path + " for " + url, links.get(url).contains(path));
	}

	private void checkAnchor(Multimap<String,String> anchors, String url, String anchor) {
		assertTrue("Anchor for URL " + url + " not found in [" + String.join(", ", anchors.keySet()) + "]",
				anchors.containsKey(url));
		assertTrue("Wrong anchor text " + anchor + " for " + url, anchors.get(url).contains(anchor));
	}

	private void checkTitle(Resource resource, String title) {
		assertNotNull(resource);
		assertTrue("Wrong instance type of Resource: " + resource.getClass(), resource instanceof HTMLResource);
		JSONObject head = resource.getMetaData().optJSONObject("Head");
		if (title != null) {
			assertNotNull(head);
			assertTrue("No title found", head.has(ResourceConstants.HTML_TITLE));
			assertEquals(title, head.get(ResourceConstants.HTML_TITLE));
		} else {
			assertFalse(head.has(ResourceConstants.HTML_TITLE));
		}
	}

	private void checkExtractedAttributes(Resource resource, String... attributes) throws JSONException {
		assertNotNull(resource);
		assertTrue("Wrong instance type of Resource: " + resource.getClass(), resource instanceof HTMLResource);
		JSONArray metas = resource.getMetaData().getJSONObject("Head").getJSONArray("Metas");
		assertNotNull(metas);
		JSONObject meta = metas.getJSONObject(0);
		assertEquals(attributes.length / 2, meta.length());
		for (int i = 0; i < attributes.length; i += 2) {
			String key = attributes[i];
			assertNotNull(meta.get(key));
			assertEquals(meta.get(key), attributes[i + 1]);
		}
	}

	private void checkLinks(Resource resource, String[][] expectedLinks) {
		assertNotNull(resource);
		assertTrue("Wrong instance type of Resource: " + resource.getClass(), resource instanceof HTMLResource);
		MetaData md = resource.getMetaData();
		LOG.info(md.toString());
		Multimap<String, String> links = ArrayListMultimap.create();
		Multimap<String, String> anchors = ArrayListMultimap.create();
		JSONObject head = md.optJSONObject("Head");
		if (head != null) {
			// <base href="http://www.example.com/" />
			String baseUrl = (String) head.opt("Base");
			if (baseUrl != null) {
				links.put(baseUrl, "__base__");
			}
			// <meta http-equiv="Refresh" content="5; URL=http://www.example.com/redirected.html" />
			JSONArray metas = head.optJSONArray("Metas");
			if (metas != null) {
				for (int i = 0; i < metas.length(); i++) {
					JSONObject o = metas.optJSONObject(i);
					String httpEquiv = o.optString("http-equiv");
					if (httpEquiv != null && httpEquiv.equalsIgnoreCase("Refresh")) {
						String metaRefreshTarget = o.optString("content");
						if (metaRefreshTarget != null) {
							metaRefreshTarget = metaRefreshTarget.replaceFirst("(?i)(?:^\\d+\\s*;)?\\s*url=", "");
							links.put(metaRefreshTarget, "__meta_refresh__");
						}
					}
				}
			}
		}
		// extract outlinks
		List<JSONArray> linkArrays = new ArrayList<JSONArray>();
		if (md.optJSONArray("Links") != null) {
			linkArrays.add(md.optJSONArray("Links"));
		}
		try {
			if (md.getJSONObject("Head") != null && md.getJSONObject("Head").getJSONArray("Link") != null) {
				linkArrays.add(md.getJSONObject("Head").getJSONArray("Link"));
			}
		} catch (JSONException e1) {
		}
		for (JSONArray ldata : linkArrays) {
			for (int i = 0; i < ldata.length(); i++) {
				JSONObject o = ldata.optJSONObject(i);
				try {
					String url;
					if (o.has("url")) {
						url = o.getString("url");
					} else if (o.has("href")) {
						url = o.getString("href");
					} else {
						fail("No URL found in: " + o);
						continue;
					}
					links.put(url, o.getString("path"));
					LOG.info(" found link: " + url + " " + o.getString("path"));
					if (o.has("text")) {
						anchors.put(url, o.getString("text"));
					} else if (o.has("alt")) {
						anchors.put(url, o.getString("alt"));
					}
				} catch (JSONException e) {
					fail("Failed to extract URL from link: " + e.getMessage());
				}
			}
		}
		assertEquals("Unexpected number of links", expectedLinks.length, links.size());
		for (String[] l : expectedLinks) {
			checkLink(links, l[0], l[1]);
			if (l.length > 2 && l[2] != null) {
				checkAnchor(anchors, l[0], l[2]);
			}
		}
	}

	private void checkExtractHtmlLangAttribute(Resource resource, String... langAttributes)
			throws JSONException {
		assertNotNull(resource);
		assertTrue("Wrong instance type of Resource: " + resource.getClass(), resource instanceof HTMLResource);
		JSONArray metas = resource.getMetaData().getJSONObject("Head").getJSONArray("Metas");
		assertNotNull(metas);
		JSONObject meta = metas.getJSONObject(0);
		for (int i = 0; i < langAttributes.length; i += 2) {
			String key = langAttributes[i];
			assertNotNull(meta.get(key));
			assertEquals(meta.get(key), langAttributes[i+1]);
		}
	}

	public void testLinkExtraction() throws ResourceParseException, IOException {
		String testFileName = "link-extraction-test.warc";
		ResourceProducer producer = ProducerUtils.getProducer(getClass().getResource(testFileName).getPath());
		ResourceFactoryMapper mapper = new ExtractingResourceFactoryMapper();
		ExtractingResourceProducer extractor = 
				new ExtractingResourceProducer(producer, mapper);
		extractor.getNext(); // skip warcinfo record
		String[][] html4links = {
				{"http://www.example.com/", "__base__"},
				{"http://www.example.com/redirected.html", "__meta_refresh__"},
				{"background.jpg", "BODY@/background"},
				{"http://www.example.com/a-href.html", "A@/href"},
				{"#anchor", "A@/href"},
				{"image.png", "IMG@/src"},
				{"image.gif", "IMG@/src"},
				{"http://example.com/image-description.html#image.gif", "IMG@/longdesc"},
				{"helloworld.swf", "OBJECT@/data"},
				{"http://www.example.com/shakespeare.html", "Q@/cite"},
				{"http://www.example.com/shakespeare-long.html", "BLOCKQUOTE@/cite"}
		};
		Resource resource = extractor.getNext();
		checkTitle(resource, "Test XHTML Link Extraction");
		checkLinks(resource, html4links);
		String[][] html5links = {
				{"http:///www.example.com/video.html", "LINK@/href", null, "canonical"},
				{"video.rss", "LINK@/href", null, "alternate"},
				{"https://archive.org/download/WebmVp8Vorbis/webmvp8.gif", "VIDEO@/poster"},
				{"https://archive.org/download/WebmVp8Vorbis/webmvp8.webm", "SOURCE@/src"},
				{"https://archive.org/download/WebmVp8Vorbis/webmvp8_512kb.mp4", "SOURCE@/src"},
				{"https://archive.org/download/WebmVp8Vorbis/webmvp8.ogv", "SOURCE@/src"}
		};
		resource = extractor.getNext();
		checkTitle(resource, "Test HTML5 Video Tag");
		checkLinks(resource, html5links);
		String[][] html5links2 = {
				{"http://www.example.com/", "A@/href"},
		};
		resource = extractor.getNext();
		checkTitle(resource, "Testing poor HTML5");
		checkLinks(resource, html5links2);
		String[][] fbVideoLinks = {
				{"https://www.facebook.com/facebook/videos/10153231379946729/", "BLOCKQUOTE@/cite"},
				{"https://www.facebook.com/facebook/videos/10153231379946729/", "A@/href"},
				{"https://www.facebook.com/facebook/", "A@/href"},
				{"https://www.facebook.com/facebook/videos/10153231379946729/", "DIV@/data-href"}
		};
		resource = extractor.getNext();
		checkTitle(resource, "fb-video - Embedded Videos - Social Plugins");
		checkLinks(resource, fbVideoLinks);
		String[][] dataHrefLinks = {
				{"standard.css", "LINK@/href", null, "stylesheet"},
				{"https://www.facebook.com/elegantthemes/videos/10153760379211923/", "DIV@/data-href"},
				{"https://www.facebook.com/facebook/videos/10153231379946729/", "DIV@/data-href"},
				{"https://www.facebook.com/facebook/videos/10153231379946729/", "BLOCKQUOTE@/cite"},
				{"https://www.facebook.com/facebook/videos/10153231379946729/", "A@/href"},
				{"https://www.facebook.com/facebook/", "A@/href"},
				{"//edge.flowplayer.org/bauhaus.webm", "SOURCE@/src"},
				{"//edge.flowplayer.org/bauhaus.mp4", "SOURCE@/src"},
				{"//edge.flowplayer.org/functional.webm", "BUTTON@/data-href"},
				{"/content-page", "ARTICLE@/data-href"},
				{"/content-page",  "A@/href"},
				{"/tags/content","A@/href"},
				{"/tags/headlines", "A@/href"},
				{"http://grabaperch.com", "DIV@/data-href"},
				{"green.css", "LINK@/data-href"},
				{"blue.css", "LINK@/data-href"},
				{"http://codecanyon.net/user/CodingJack", "A@/data-href"},
				{"jackbox/img/thumbs/4.jpg",  "IMG@/src"},
				{"//venobox-destination", "A@/data-href"},
				{"#", "A@/href"},
				{"http://www.youtube.com/v/itTskyFLSS8&rel=0&autohide=1&showinfo=0&autoplay=1", "DIV@/data-href"},
				{"#", "A@/href"},
				{"http://www.youtube.com/v/itTskyFLSS8&rel=0&autohide=1&showinfo=0", "IFRAME@/src"}
		};
		resource = extractor.getNext();
		checkTitle(resource, null); // empty title!
		checkLinks(resource, dataHrefLinks);
		String[][] fbSocialLinks = {
				{"http://www.your-domain.com/your-page.html", "DIV@/data-uri"},
				{"https://developers.facebook.com/docs/plugins/comments#configurator", "DIV@/data-href"},
				{"https://www.facebook.com/zuck/posts/10102735452532991?comment_id=1070233703036185", "DIV@/data-href"},
				{"https://www.facebook.com/zuck", "DIV@/data-href"},
				{"https://developers.facebook.com/docs/plugins/", "DIV@/data-href"},
				{"https://www.facebook.com/facebook", "DIV@/data-href"},
				{"https://www.facebook.com/facebook", "BLOCKQUOTE@/cite"},
				{"https://www.facebook.com/facebook", "A@/href"},
				{"http://www.your-domain.com/your-page.html", "DIV@/data-href"}
		};
		resource = extractor.getNext();
		// fragment without head and no title
		checkLinks(resource, fbSocialLinks);
		String[][] onClickLinks = {
				{"webpage.html", "DIV@/onclick"},
				{"index.html", "INPUT@/onclick"},
				{"http://www.x.com/", "INPUT@/onclick"},
				{"button-child.php", "INPUT@/onclick"},
				{"http://example.com/", "INPUT@/onclick"},
				{"http://example.com/location/href/1.html", "INPUT@/onclick"},
				{"http://example.com/location/href/2.html", "INPUT@/onclick"}
		};
		resource = extractor.getNext();
		checkTitle(resource, "Test Extraction of URLs from INPUT onClick Attributes");
		checkLinks(resource, onClickLinks);
		String[][] escapedEntitiesLinks = {
				{"http://www.example.com/", "__base__"},
				{"http://www.example.com/redirected.html", "__meta_refresh__"},
				{"/view?id=logo&action=edit", "A@/href"},
				{"http://www.example.com/search?q=examples&n=20", "A@/href", "Examples & more"},
				{"/view?id=logo&res=420x180", "STYLE/#text"},
				{"https://img.example.org/view?id=867&res=10x16", "IMG@/src",
					"image URL containing escaped ampersand (\"&amp;\")" }
		};
		resource = extractor.getNext();
		assertNotNull(resource);
		checkTitle(resource, "Title – \"Title\" written using character entities");
		checkLinks(resource, escapedEntitiesLinks);
		MetaData md = resource.getMetaData();
		JSONArray metas = md.getJSONObject(ResourceConstants.HTML_HEAD).getJSONArray(ResourceConstants.HTML_META_TAGS);
		for (int i = 0; i < metas.length(); i++) {
			JSONObject o = metas.optJSONObject(i);
			String property = o.optString("property");
			if (property.equals("og:description")) {
				String content = o.optString("content");
				assertEquals(content, "Apostrophe's description");
			}
		}
		String[][] exampleLinks = { { "https://example.org/", "A@/href",
				"Anchor text with white space character entities and HTML block elements" } };
		resource = extractor.getNext();
		assertNotNull(resource);
		checkTitle(resource, "Test Anchor Text Extraction With Whitespace");
		checkLinks(resource, exampleLinks);
	}

	public void testTextExtraction() throws ResourceParseException, IOException {
		String testFileName = "text-extraction-test.warc";
		ResourceProducer producer = ProducerUtils.getProducer(getClass().getResource(testFileName).getPath());
		ResourceFactoryMapper mapper = new ExtractingResourceFactoryMapper();
		ExtractingResourceProducer extractor = new ExtractingResourceProducer(producer, mapper);
		extractor.getNext(); // skip warcinfo record
		Resource resource = extractor.getNext();
		assertNotNull(resource);
		assertTrue("Wrong instance type of Resource: " + resource.getClass(), resource instanceof HTMLResource);
		checkTitle(resource, "White space and paragraph breaks when converting HTML to text");
		String text = resource.getMetaData().getString(ResourceConstants.HTML_TEXT);
		System.out.println(text);
		assertTrue(text.contains("text\nThere should be a paragraph break after <h1-h6>"));
		assertTrue(text.contains("«foobarfoo»"));
		assertFalse(text.contains("«foo bar foo»"));
		assertTrue(text.contains("comments: nospace"));
		assertFalse(text.contains("before an imageand after"));
		assertFalse(text.contains("firstsecond line"));
		assertFalse(text.contains("first linediv element"));
		assertFalse(text.contains("div elementsecond line"));
		assertFalse(text.contains("2017by"));
		assertFalse(text.contains("Heath9"));
		assertFalse(text.contains("readAdd"));
		assertTrue(text.contains("read\nAdd"));
		assertFalse(text.contains("first linesecond line"));
		assertTrue(text.contains("first line\nsecond line\n<entity>"));
		// TODO: CDATA in mathml not correctly parsed
		// assertTrue(text.matches("CDATA in MathML:\\W*x<y"));
	}

	public void testTitleExtraction() throws ResourceParseException, IOException {
		String testFileName = "title-extraction-embedded-SVG.warc";
		ResourceProducer producer = ProducerUtils.getProducer(getClass().getResource(testFileName).getPath());
		ResourceFactoryMapper mapper = new ExtractingResourceFactoryMapper();
		ExtractingResourceProducer extractor = 
				new ExtractingResourceProducer(producer, mapper);
		Resource resource = extractor.getNext();
		checkTitle(resource, "Testing title extraction with embedded SVG");
	}

	public void testHtmlLanguageAttributeExtraction() throws ResourceParseException, IOException {
		String testFileName = "html-lang-attribute.warc";
		ResourceProducer producer = ProducerUtils.getProducer(getClass().getResource(testFileName).getPath());
		ResourceFactoryMapper mapper = new ExtractingResourceFactoryMapper();
		ExtractingResourceProducer extractor = new ExtractingResourceProducer(producer, mapper);
		checkExtractHtmlLangAttribute(extractor.getNext(), "name", "HTML@/lang", "content", "en");
		checkExtractHtmlLangAttribute(extractor.getNext(), "name", "HTML@/lang", "content", "zh-CN");
		checkExtractHtmlLangAttribute(extractor.getNext(), "name", "HTML@/lang", "content", "cs-cz");
		checkExtractHtmlLangAttribute(extractor.getNext(), "name", "HTML@/lang", "content", "en");
		checkExtractHtmlLangAttribute(extractor.getNext(), "name", "HTML@/xml:lang", "content", "es-MX");
	}

	public void testBodyMetaElements() throws ResourceParseException, IOException {
		String testFileName = "meta-itemprop.warc";
		ResourceProducer producer = ProducerUtils.getProducer(getClass().getResource(testFileName).getPath());
		ResourceFactoryMapper mapper = new ExtractingResourceFactoryMapper();
		ExtractingResourceProducer extractor = new ExtractingResourceProducer(producer, mapper);
		checkExtractedAttributes(extractor.getNext(), "name", "robots", "content", "index,follow");
	}

	public void testHtmlParserEntityDecoding() {
		String[][] entities = { //
				/* ampersand */
				{ "&amp;", "&" },
				/* apostrophe */
				{ "&apos;", "'" },
				{ "&#039;", "'" },
				/* comma */
				{ "&comma;", "," },
				/* % percent */
				{ "&percnt;", "%" },
				/* ’ right single quotation mark */
				{ "&rsquo;", "\u2019" },
				/* » right-pointing double angle quotation mark */
				{ "&raquo;", "\u00bb" },
				/* … horizontal ellipsis */
				{ "&hellip;", "\u2026" },
				/* 𤆑 CJK UNIFIED IDEOGRAPH-24191 */
				{ "&#x24191;", new String(Character.toChars(0x24191)) },
				/* 😊 U+1F60A SMILING FACE WITH SMILING EYES */
				{ "&#x1F60A;", new String(Character.toChars(0x1f60a)) },
				/*
				 * must not decode "&or" in "&order" as "&or;" (∨ U+2228) to
				 * avoid that unescaped ampersands in URLs cause erroneous
				 * replacements
				 */
				{ "https://example.org/search?q=example&order=lexical",
						"https://example.org/search?q=example&order=lexical" },
				{ "https://example.org/search?q=example&amp;order=lexical",
					"https://example.org/search?q=example&order=lexical" },
				{ "&or;", "\u2228" },
				/* 👎 U+1F44E THUMBS DOWN SIGN  (must not decode 0x1f44) */
				{ "&#x1f44e;", new String(Character.toChars(0x1f44e)) },
				/*
				 * invalid Unicode code point: make sure that exceptions are
				 * handled, the actual character may appear as (? or �)
				 */
				{ "&#xd83f;", null }, // single char of surrogate pair
				{ "&#x110000;", null }, //
				{ "&#2013266048;", null }, //
				{ "&#0;", null }, //
				/*
				 * for better text conversion, some entities might be decoded
				 * even if not closed by a ;
				 */
				{ "&nbsp&nbsp&nbsp", "\u00a0\u00a0\u00a0" }, //
				{ "&nbsp", "\u00a0" }, //
				{ "&order", "&order" }, //
				/* but never in URLs */
				{ "https://example.org/search?q=example&nbsp=value",
						"https://example.org/search?q=example&nbsp=value" }, //
				/*
				 * test more aggressive replacement in text mode (not
				 * inAttribute)
				 */
				{ "law&order", "law&order", "false" }, //
				{ "a &or; b", "a \u2228 b", "false" }, //
				{ "a &or b", "a &or b", "false" }, //
				{ "a &amp b", "a & b", "false" }, //
				/* comparison of text vs. attribute mode */
				{ "a&nbsp=&nbsp;b", "a&nbsp=\u00a0b", "true" }, //
				{ "a&nbsp=&nbsp;c", "a\u00a0=\u00a0c", "false" }, //
				{ "a&nbsp=&nbsp&order=true", "a&nbsp=\u00a0&order=true", "true" }, //
				{ "a&nbsp=&nbsp&order=true", "a\u00a0=\u00a0&order=true", "false" }, //
		};
		for (String[] ent : entities) {
			String decoded = ExtractingParseObserver.decodeCharEnt(ent[0]);
			if (ent.length > 2) {
				// test for text nodes
				decoded = ExtractingParseObserver.decodeCharEnt(ent[0], Boolean.valueOf(ent[2]));
			}
			if (ent[1] != null) {
				assertEquals("Entity " + ent[0] + " not properly decoded", ent[1], decoded);
			}
		}
	}

}
