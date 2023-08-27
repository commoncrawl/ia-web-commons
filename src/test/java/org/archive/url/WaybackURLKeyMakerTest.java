package org.archive.url;

import java.net.URISyntaxException;

import junit.framework.TestCase;

public class WaybackURLKeyMakerTest extends TestCase {

	public void testMakeKey() throws URISyntaxException {
		WaybackURLKeyMaker km = new WaybackURLKeyMaker();
		assertEquals("-", km.makeKey(null));
		assertEquals("-", km.makeKey(""));
		assertEquals("dskgfljsdlkgjslkj)/", km.makeKey("dskgfljsdlkgjslkj"));
		assertEquals("filedesc:foo.arc.gz", km.makeKey("filedesc:foo.arc.gz"));
		assertEquals("filedesc:/foo.arc.gz", km.makeKey("filedesc:/foo.arc.gz"));
		assertEquals("filedesc://foo.arc.gz", km.makeKey("filedesc://foo.arc.gz"));
		assertEquals("warcinfo:foo.warc.gz", km.makeKey("warcinfo:foo.warc.gz"));
		assertEquals("com,alexa)", km.makeKey("dns:alexa.com"));
		assertEquals("org,archive)", km.makeKey("dns:archive.org"));
		assertEquals("org,archive)/", km.makeKey("http://archive.org/"));
		assertEquals("org,archive)/goo", km.makeKey("http://archive.org/goo/"));
		assertEquals("org,archive)/goo", km.makeKey("http://archive.org/goo/?"));
		assertEquals("org,archive)/goo?a&b", km.makeKey("http://archive.org/goo/?b&a"));
		assertEquals("org,archive)/goo?a=1&a=2&b", km.makeKey("http://archive.org/goo/?a=2&b&a=1"));
		assertEquals("org,archive)/", km.makeKey("http://archive.org:/"));
		assertEquals("ua,1kr)/newslist.html?tag=%e4%ee%f8%ea%ee%eb%fc%ed%ee%e5",
				km.makeKey("http://1kr.ua/newslist.html?tag=%E4%EE%F8%EA%EE%EB%FC%ED%EE%E5"));
		assertEquals("com,aluroba)/tags/%c3%ce%ca%c7%d1%e5%c7.htm",
				km.makeKey("http://www.aluroba.com/tags/%C3%CE%CA%C7%D1%E5%C7.htm"));
	}

}
