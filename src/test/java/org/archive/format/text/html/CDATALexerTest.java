package org.archive.format.text.html;

import org.htmlparser.Node;
import org.htmlparser.lexer.Page;
import org.htmlparser.nodes.TagNode;
import org.htmlparser.nodes.TextNode;
import org.htmlparser.util.ParserException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CDATALexerTest {
	CDATALexer l;
	Node n;
	private CDATALexer makeLexer(String html) {
		CDATALexer t = new CDATALexer();
		t.setPage(new Page(html));
		return t;
	}

	@Test
	public void testNextNode() throws ParserException {
		l = makeLexer("<a href=\"foo\">blem</a>");
		n = l.nextNode();
		assertFalse(l.inCSS());
		assertFalse(l.inJS());
		assertTrue(NodeUtils.isNonEmptyOpenTagNodeNamed(n, "A"));
		assertEquals("foo",((TagNode)n).getAttribute("HREF"));
		n = l.nextNode();
		assertTrue(NodeUtils.isTextNode(n));
		assertEquals("blem",((TextNode)n).getText());
		n = l.nextNode();
		assertTrue(NodeUtils.isCloseTagNodeNamed(n, "A"));
		assertNull(l.nextNode());
	}

	@Test
	public void testInJS() throws ParserException {
		l = makeLexer("<script>foo bar baz</script>");
		assertFalse(l.inCSS());
		assertFalse(l.inJS());
		n = l.nextNode();
		assertFalse(l.inCSS());
		assertFalse(l.inJS());
		assertTrue(NodeUtils.isNonEmptyOpenTagNodeNamed(n, "SCRIPT"));
		n = l.nextNode();
		assertFalse(l.inCSS());
		assertTrue(l.inJS());
		assertTrue(NodeUtils.isTextNode(n));
		assertEquals("foo bar baz",((TextNode)n).getText());
		n = l.nextNode();
		assertFalse(l.inCSS());
		assertFalse(l.inJS());
		assertTrue(NodeUtils.isCloseTagNodeNamed(n, "SCRIPT"));
	}

	@Test
	public void testInCSS() throws ParserException {
		l = makeLexer("<style>foo bar baz</style>");
		assertFalse(l.inCSS());
		assertFalse(l.inJS());
		n = l.nextNode();
		assertFalse(l.inCSS());
		assertFalse(l.inJS());
		assertTrue(NodeUtils.isNonEmptyOpenTagNodeNamed(n, "STYLE"));
		n = l.nextNode();
		assertTrue(l.inCSS());
		assertFalse(l.inJS());
		assertTrue(NodeUtils.isTextNode(n));
		assertEquals("foo bar baz",((TextNode)n).getText());
		n = l.nextNode();
		assertFalse(l.inCSS());
		assertFalse(l.inJS());
		assertTrue(NodeUtils.isCloseTagNodeNamed(n, "STYLE"));
	}

	public void testInCSSEmpty() throws ParserException {
		l = makeLexer("<style></style>");
		assertFalse(l.inCSS());
		assertFalse(l.inJS());
		n = l.nextNode();
		assertFalse(l.inCSS());
		assertFalse(l.inJS());
		assertTrue(NodeUtils.isNonEmptyOpenTagNodeNamed(n, "STYLE"));
		n = l.nextNode();
		assertFalse(l.inCSS());
		assertFalse(l.inJS());
		assertTrue(NodeUtils.isCloseTagNodeNamed(n, "STYLE"));
	}

	public void testInCSSBachelorTag() throws ParserException {
		l = makeLexer("<style />");
		assertFalse(l.inCSS());
		assertFalse(l.inJS());
		n = l.nextNode();
		assertFalse(l.inCSS());
		assertFalse(l.inJS());
		assertTrue(NodeUtils.isTagNode(n));
		assertTrue(((TagNode) n).isEmptyXmlTag());
		assertEquals(((TagNode) n).getTagName(), "STYLE");
		n = l.nextNode();
		assertFalse(l.inCSS());
		assertFalse(l.inJS());
		assertNull(n);
	}
	
	public void testInJSComment() throws ParserException {
		assertJSContentWorks("//<!--\n foo bar baz\n //-->");
		assertJSContentWorks("<!-- foo bar baz -->");
		assertJSContentWorks("//<!-- foo bar baz -->");
		assertJSContentWorks("<!-- foo bar baz //-->");
		assertJSContentWorks("\n//<!-- foo bar baz\n //-->");
		assertJSContentWorks("if(1 < 2) { foo(); } ");
		assertJSContentWorks("if(1 <n) { foo(); } ");
		assertJSContentWorks("document.write(\"<b>bold</b>\"); ");
		assertJSContentWorks("document.write(\"<script>bold<\\/script>\"); ");
		assertJSContentWorks("<![CDATA[\n if(i<n) { foo() } // a comment \n ]]> ");
		assertJSContentWorks("var script = '<script>alert(\"hello, world!\")<\\/script>'; console.log(script); ");
		assertJSContentWorks("\n"
				+ "        var _hmt = _hmt || [];\n"
				+ "        (function() {\n"
				+ "        var hm = document.createElement(\"script\");\n"
				+ "        hm.src = \"https://#/hm.js?aba99f7fd4116f6c8c3d1650e8f8ec17\";\n"
				+ "        var s = document.getElementsByTagName(\"script\")[0]; \n"
				+ "        s.parentNode.insertBefore(hm, s);\n"
				+ "        })();\n"
				+ "    ");
		/*
		 * The parser fails on unfinished HTML comments inside script or style.
		 */
		// assertJSContentWorks("<!-- foo bar baz ");
	}
	
	private void assertJSContentWorks(String js) throws ParserException {
		String html = String.format("<script>%s</script>",js);
		l = makeLexer(html);
		assertFalse(l.inCSS());
		assertFalse(l.inJS());
		n = l.nextNode();
		assertFalse(l.inCSS());
		assertFalse(l.inJS());
		assertTrue(NodeUtils.isNonEmptyOpenTagNodeNamed(n, "SCRIPT"));
		n = l.nextNode();
		assertFalse(l.inCSS());
		assertTrue(l.inJS());
		assertTrue(NodeUtils.isTextNode(n));
		assertEquals(js,((TextNode)n).getText());
		n = l.nextNode();
		assertFalse(l.inCSS());
		assertFalse(l.inJS());
		assertTrue(NodeUtils.isCloseTagNodeNamed(n, "SCRIPT"));
	}
	
	
//	private void dumpParse(String html) throws ParserException {
//		System.out.println("SOPARSE("+html+")");
//		l = makeLexer(html);
//		while(true) {
//			n = l.nextNode();
//			if(n == null) {
//				break;
//			}
//			String state = String.format("%s%s", 
//					l.inCSS() ? "C" : "", l.inJS() ? "J" : "");
//			if(NodeUtils.isRemarkNode(n)) {
//				System.out.format("---COMMENT(%s)(%s)\n", state, ((RemarkNode)n).getText());
//			} else if(NodeUtils.isTextNode(n)) {
//				System.out.format("---TEXT(%s)(%s)\n", state, ((TextNode)n).getText());				
//			} else {
//				TagNode tn = (TagNode) n;
//				if(tn.isEmptyXmlTag()) {
//					System.out.format("---EMPTY(%s)(%s)\n", state, tn.getTagName());
//				} else if(tn.isEndTag()) {
//					System.out.format("---END(%s)(%s)\n", state, tn.getTagName());					
//				} else {
//					System.out.format("---OPEN(%s)(%s)\n", state, tn.getTagName());					
//				}
//			}
//		}
//		System.out.println("EOPARSE");
//	}
	
	
}
