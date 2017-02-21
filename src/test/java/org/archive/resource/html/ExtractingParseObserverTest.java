package org.archive.resource.html;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.archive.extract.ExtractingResourceFactoryMapper;
import org.archive.extract.ExtractingResourceProducer;
import org.archive.extract.ProducerUtils;
import org.archive.extract.ResourceFactoryMapper;
import org.archive.resource.MetaData;
import org.archive.resource.Resource;
import org.archive.resource.ResourceParseException;
import org.archive.resource.ResourceProducer;
import org.htmlparser.nodes.TextNode;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
		assertTrue("Link with URL " + url + " not found", links.containsKey(url));
		assertTrue("Wrong path " + path + " for " + url, links.get(url).contains(path));
	}

	private void checkLinks(Resource resource, String[][] expectedLinks) {
		assertNotNull(resource);
		assertTrue(resource instanceof HTMLResource);
		MetaData md = resource.getMetaData();
		LOG.info(md.toString());
		Multimap<String, String> links = ArrayListMultimap.create();
		try {
			// <base href="http://www.example.com/" />
			String baseUrl = (String) md.getJSONObject("Head").opt("Base");
			if (baseUrl != null) {
				links.put(baseUrl, "__base__");
			}
			// <meta http-equiv="Refresh" content="5; URL=http://www.example.com/redirected.html" />
			JSONArray metas = md.getJSONObject("Head").optJSONArray("Metas");
			if (metas != null) {
				for (int i = 0; i < metas.length(); i++) {
					JSONObject o = (JSONObject) metas.optJSONObject(i);
					if (o.getString("http-equiv").equals("Refresh")) {
						String metaRefreshTarget = o.getString("content").replaceFirst("(?i)(?:^\\d+\\s*;)?\\s*url=", "");
						LOG.info(metaRefreshTarget);
						links.put(metaRefreshTarget, "__meta_refresh__");
					}
				}
			}
		} catch (JSONException e) {
			fail("Failed to parse JSON: " + e.getMessage());
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
				JSONObject o = (JSONObject) ldata.optJSONObject(i);
				try {
					String url = o.getString("url");
					links.put(url, o.getString("path"));
					LOG.info(" found link: " + o.getString("url") + " " + o.getString("path"));
				} catch (JSONException e) {
					fail("Failed to extract URL from link: " + e.getMessage());
				}
			}
		}
		assertEquals("Unexpected number of links", expectedLinks.length, links.size());
		for (String[] l : expectedLinks) {
			checkLink(links, l[0], l[1]);
		}
	}

	public void testLinkExtraction() throws ResourceParseException, IOException {
		String testFileName = "link-extraction-test.warc";
		ResourceProducer producer = ProducerUtils.getProducer(getClass().getResource(testFileName).toString());
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
		checkLinks(extractor.getNext(), html4links);
		String[][] html5links = {
				{"http:///www.example.com/video.html", "LINK@/href", "canonical"},
				{"video.rss", "LINK@/href", "alternate"},
				{"https://archive.org/download/WebmVp8Vorbis/webmvp8.gif", "VIDEO@/poster"},
				{"https://archive.org/download/WebmVp8Vorbis/webmvp8.webm", "SOURCE@/src"},
				{"https://archive.org/download/WebmVp8Vorbis/webmvp8_512kb.mp4", "SOURCE@/src"},
				{"https://archive.org/download/WebmVp8Vorbis/webmvp8.ogv", "SOURCE@/src"}
		};
		checkLinks(extractor.getNext(), html5links);
		String[][] fbVideoLinks = {
				{"https://www.facebook.com/facebook/videos/10153231379946729/", "BLOCKQUOTE@/cite"},
				{"https://www.facebook.com/facebook/videos/10153231379946729/", "A@/href"},
				{"https://www.facebook.com/facebook/", "A@/href"},
		};
		checkLinks(extractor.getNext(), fbVideoLinks);
	}

}
