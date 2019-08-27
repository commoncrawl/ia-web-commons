package org.archive.resource.html;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.archive.format.text.html.ParseObserver;
import org.htmlparser.Attribute;
import org.htmlparser.nodes.RemarkNode;
import org.htmlparser.nodes.TagNode;
import org.htmlparser.nodes.TextNode;
import org.htmlparser.util.Translate;

public class ExtractingParseObserver implements ParseObserver {

	HTMLMetaData data;
	Stack<ArrayList<String>> openAnchors;
	Stack<StringBuilder> openAnchorTexts;
	StringBuilder textExtract;
	String title = null;
	boolean inTitle = false;
	boolean inPre = false;

	protected static String cssUrlPatString = 
		"url\\s*\\(\\s*([^)\\s]{1,8000}?)\\s*\\)";
	protected static String cssUrlTrimPatString =
			"^(?:\\\\?[\"'])+|(?:\\\\?[\"'])+$";
	protected static String cssImportNoUrlPatString = 
			"@import\\s+((?:'[^']+')|(?:\"[^\"]+\")|(?:\\('[^']+'\\))|(?:\\(\"[^\"]+\"\\))|(?:\\([^)]+\\))|(?:[a-z0-9_.:/\\\\-]+))\\s*;";

	protected static Pattern cssImportNoUrlPattern = Pattern
			.compile(cssImportNoUrlPatString);

	protected static Pattern cssUrlPattern = Pattern.compile(cssUrlPatString);

	protected static Pattern cssUrlTrimPattern = Pattern.compile(cssUrlTrimPatString);

	protected static String jsOnClickUrl1PatString = 
			"(?i)^(?:javascript:)?(?:(?:window|top|document|self|parent)\\.)?location(?:\\.href)?\\s*=\\s*('|&#39;)([^'\"]{3,256})\\1$";
	protected static String jsOnClickUrl2PatString = 
			"(?i)^(?:javascript:)?(?:window|parent)\\.open\\((['\"]|&#39;)([^\"']{3,256}?)\\1[,)]";
	protected static Pattern[] jsOnClickUrlPatterns = {
			Pattern.compile(jsOnClickUrl1PatString),
			Pattern.compile(jsOnClickUrl2PatString)
	};

	protected static Pattern wsPattern = Pattern.compile("\\s+");

	private final static int MAX_TEXT_LEN = 100;

	private final static String[] BLOCK_ELEMENTS = { "address", "article", "aside", "blockquote", "body", "br",
			"button", "canvas", "caption", "col", "colgroup", "dd", "div", "dl", "dt", "embed", "fieldset",
			"figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hgroup", "hr",
			"li", "map", "noscript", "object", "ol", "output", "p", "pre", "progress", "section", "table", "tbody",
			"textarea", "tfoot", "th", "thead", "tr", "ul", "video" };
	private static final Set<String> blockElements;
	/* inline elements which content is not melted with surrounding words */
	private final static String[] INLINE_ELEMENTS_SPACING = { "address", "cite", "details", "datalist", "iframe", "img",
			"input", "label", "legend", "optgroup", "q", "select", "summary", "tbody", "td", "time" };
	private static final Set<String> inlineSpacingElements;
	static {
		blockElements = new HashSet<String>();
		for (String el : BLOCK_ELEMENTS) {
			blockElements.add(el.toUpperCase(Locale.ROOT));
		}
		inlineSpacingElements = new HashSet<String>();
		for (String el : INLINE_ELEMENTS_SPACING) {
			inlineSpacingElements.add(el.toUpperCase(Locale.ROOT));
		}
	}

	private static final String PATH = "path";
	private static final String PATH_SEPARATOR = "@/";
	private static final Map<String, TagExtractor> extractors;
	private static final Set<String> globalHrefAttributes;
	static {
		extractors = new HashMap<String,ExtractingParseObserver.TagExtractor>();
		extractors.put("A", new AnchorTagExtractor());
		extractors.put("APPLET", new AppletTagExtractor());
		extractors.put("AREA", new AreaTagExtractor());
		extractors.put("BASE", new BaseTagExtractor());
		extractors.put("DIV", new DivTagExtractor());
		extractors.put("EMBED", new EmbedTagExtractor());
		extractors.put("FORM", new FormTagExtractor());
		extractors.put("FRAME", new FrameTagExtractor());
		extractors.put("IFRAME", new IFrameTagExtractor());
		extractors.put("IMG", new ImgTagExtractor());
		extractors.put("INPUT", new InputTagExtractor());
		extractors.put("LINK", new LinkTagExtractor());
		extractors.put("META", new MetaTagExtractor());
		extractors.put("OBJECT", new ObjectTagExtractor());
		extractors.put("SCRIPT", new ScriptTagExtractor());
		extractors.put("Q", new QuotationLinkTagExtractor());
		extractors.put("BLOCKQUOTE", new QuotationLinkTagExtractor());
		extractors.put("DEL", new QuotationLinkTagExtractor());
		extractors.put("INS", new QuotationLinkTagExtractor());
		// HTML5:
		extractors.put("BUTTON", new ButtonTagExtractor());
		extractors.put("MENUITEM", new MenuitemTagExtractor());
		extractors.put("VIDEO", new EmbedVideoTagExtractor());
		extractors.put("AUDIO", new EmbedTagExtractor());
		extractors.put("TRACK", new EmbedTagExtractor());
		extractors.put("SOURCE", new EmbedTagExtractor());

		globalHrefAttributes = new HashSet<String>();
		globalHrefAttributes.add("background");
		globalHrefAttributes.add("data-href");
		globalHrefAttributes.add("data-uri");
	}

	
	public ExtractingParseObserver(HTMLMetaData data) {
		this.data = data;
		openAnchors = new Stack<ArrayList<String>>();
		openAnchorTexts = new Stack<StringBuilder>();
		textExtract = new StringBuilder(8192);
	}
	
	public void handleDocumentStart() {
		// no-op
	}

	public void handleDocumentComplete() {
		if (textExtract.length() > 0) {
			data.setTextExtract(textExtract.toString());
			textExtract = new StringBuilder(8192);
		}
	}

	public void handleTagEmpty(TagNode tag) {
		handleTagOpen(tag);
	}
		
	public void handleTagOpen(TagNode tag) {
		String name = tag.getTagName();
		if(name.equals("TITLE")) {
			inTitle = !tag.isEmptyXmlTag();
			return;
		} else if (name.equals("PRE")) {
			inPre = true;
		}

		if (blockElements.contains(name)) {
			appendParagraphSeparator(textExtract);
		} else if (inlineSpacingElements.contains(name)) {
			appendSpace(textExtract);
		}

		// first the global attributes:
		Vector<Attribute> attributes = tag.getAttributesEx();
		for (Attribute a : attributes) {
			String attrName = a.getName();
			String attrValue = a.getValue();
			if (attrName == null || attrValue == null) {
				continue;
			}
			attrName = attrName.toLowerCase(Locale.ROOT);
			if (globalHrefAttributes.contains(attrName)) {
				attrValue = decodeCharEnt(attrValue);
				data.addHref(PATH,makePath(name,attrName),"url",attrValue);
			}
		}
		// TODO: style attribute, BASE(href) tag, Resolve URLs
		
		TagExtractor extractor = extractors.get(name);
		if(extractor != null) {
			extractor.extract(data, tag, this);
		}
	}

	public void handleTagClose(TagNode tag) {
		String name = tag.getTagName();

		if(inTitle) {
			inTitle = false;
			data.setTitle(title);
			title = null;
		}

		if (blockElements.contains(name)) {
			appendParagraphSeparator(textExtract);
		} else if (inlineSpacingElements.contains(name)) {
			appendSpace(textExtract);
		}

		// Only interesting if it's a </a>:
		if(name.equals("A")) {
			if(openAnchors.size() > 0) {
				// TODO: what happens here when we get unaligned (extra </a>'s?)
				ArrayList<String> vals = openAnchors.pop();
				StringBuilder text = openAnchorTexts.pop();
				if((vals != null) && (vals.size() > 0)) {
					if(text != null) {
						// contained an href - we want to ignore <a name="X"></a>:
						String trimmed = wsPattern.matcher(decodeCharEnt(text.toString()).trim()).replaceAll(" ");
						if(trimmed.length() > MAX_TEXT_LEN) {
							trimmed = trimmed.substring(0,MAX_TEXT_LEN);
						}
						if(trimmed.length() > 0) {
							vals.add("text");
							vals.add(trimmed);
						}
					}
					data.addHref(vals);
				}
			}
		} else if (tag.getTagName().equals("PRE")) {
			inPre = false;
		}
	}

	public void handleTextNode(TextNode text) {
		// TODO: OPTIMIZ: This can be a lot smarter, if StringBuilders are full,
		// this result is thrown away.

		String txt = text.getText();
		txt = decodeCharEnt(txt);
		if (inPre) {
			textExtract.append(txt);
		} else {
			txt = txt.replace('\u00a0', ' ');

			char c = ' ';
			if (textExtract.length() > 0) {
				c = textExtract.charAt(textExtract.length() - 1);
			}
			for (int i = 0; i < txt.length(); i++) {
				char c2 = txt.charAt(i);
				if (c2 == '\r' || c2 == '\n') {
					c2 = ' ';
				}
				if (!Character.isWhitespace(c) || !Character.isWhitespace(c2)) {
					textExtract.append(c2);
				}
				c = c2;
			}
		}

		String t = wsPattern.matcher(txt).replaceAll(" ");

		if(t.length() > MAX_TEXT_LEN) {
			t = t.substring(0,MAX_TEXT_LEN);
		}
		if(inTitle) {
			title = t;

		} else {
			
			for(StringBuilder s : openAnchorTexts) {
				if(s.length() >= MAX_TEXT_LEN) {
					// if we are full, parents enclosing us should be too..
					break;
				}
				if(s.length() + t.length() < MAX_TEXT_LEN) {
					s.append(t);
				} else {
					// only add as much as we can:
					s.append(t.substring(0,MAX_TEXT_LEN - s.length()));
				}
				// BUGBUG: check now for multiple trailing spaces, and strip:
			}
		}
	}

	public void handleScriptNode(TextNode text) {
		// TODO: Find (semi) obvious URLs in JS:
	}

	public void handleStyleNode(TextNode text) {
		String cssStr = decodeCharEnt(text.getText());
		patternCSSExtract(data, cssUrlPattern, cssStr);
		patternCSSExtract(data, cssImportNoUrlPattern, cssStr);
	}

	public void handleRemarkNode(RemarkNode remark) {
		// TODO no-op, right??
	}
	
	/*
	 * =========================================
	 * 
	 *  ALL ASSIST METHODS/CLASSES BELOW HERE:
	 * 
	 * =========================================
	 */
	
	
	
	private static String makePath(String tag, String attr) {
		StringBuilder sb = new StringBuilder(tag.length() + 
				PATH_SEPARATOR.length() + attr.length());
		return sb.append(tag).append(PATH_SEPARATOR).append(attr).toString();
	}
	
	private static void addBasicHrefs(HTMLMetaData data, TagNode node, String... attrs) {
		for(String attr : attrs) {
			String val = node.getAttribute(attr);
			if(val != null) {
				val = decodeCharEnt(val);
				data.addHref(PATH,makePath(node.getTagName(),attr),"url",val);
			}
		}
	}
	
	private static ArrayList<String> getAttrList(TagNode node, String... attrs) {
		ArrayList<String> l = new ArrayList<String>();
		for(String attr : attrs) {
			String val = node.getAttribute(attr);
			if(val != null) {
				val = decodeCharEnt(val);
				l.add(attr);
				l.add(val);
			}
		}
		if(l.size() == 0) {
			return null;
		}
		return l;
	}

	private static ArrayList<String> getAttrListUrl(TagNode node, 
			String urlAttr, String... optionalAttrs) {
		String url = node.getAttribute(urlAttr);
		ArrayList<String> l = null;
		if(url != null) {
			url = decodeCharEnt(url);
			l = new ArrayList<String>();
			l.add(PATH);
			l.add(makePath(node.getTagName(),urlAttr));
			l.add("url");
			l.add(url);
			// what else goes with it?
			for(String attr : optionalAttrs) {
				String val = node.getAttribute(attr);
				if(val != null) {
					val = decodeCharEnt(val);
					l.add(attr);
					l.add(val);
				}
			}
		}
		return l;
	}
	
	private static void addHrefWithAttrs(HTMLMetaData data, TagNode node, 
			String hrefAttr, String... optionalAttrs) {
		ArrayList<String> l = getAttrListUrl(node,hrefAttr,optionalAttrs);
		if(l != null) {
			data.addHref(l);
		}
	}

	private static void addHrefsOnclick(HTMLMetaData data, TagNode node) {
		String onclick = node.getAttribute("onclick");
		if (onclick != null) {
			String path = makePath(node.getTagName(), "onclick");
			for (Pattern pattern : jsOnClickUrlPatterns) {
				String url = patternJSExtract(pattern, onclick);
				if (url != null) {
					// TODO: translate?
					data.addHref(PATH, path, "url", url);
				}
			}
		}
	}

	private static void appendParagraphSeparator(StringBuilder sb) {
		int length = sb.length();
		if (length > 0) {
			// remove white space before paragraph break
			while (length > 0 && sb.charAt(length - 1) == ' ') {
				sb.deleteCharAt(--length);
			}
			if (length > 0 && sb.charAt(length - 1) != '\n') {
				sb.append('\n');
			}
		}
	}

	private static void appendSpace(StringBuilder sb) {
		int length = sb.length();
		if (length > 0) {
			char lastBufferChar = sb.charAt(length - 1);
			if (lastBufferChar != ' ' && lastBufferChar != '\n') {
				sb.append(' ');
			}
		}
	}

	private interface TagExtractor {
		public void extract(HTMLMetaData data, TagNode node, ExtractingParseObserver obs);
	}

	private static class AnchorTagExtractor implements TagExtractor {
		public void extract(HTMLMetaData data, TagNode node, ExtractingParseObserver obs) {
			ArrayList<String> l = new ArrayList<String>();
			String url = node.getAttribute("href");
			if(url != null) {
				// got data:
				url = decodeCharEnt(url);
				l.add(PATH);
				l.add(makePath("A","href"));
				l.add("url");
				l.add(url);
				for(String a : new String[] {"target","alt","title","rel","hreflang","type"}) {
					String v = node.getAttribute(a);
					if(v != null) {
						v = decodeCharEnt(v);
						l.add(a);
						l.add(v);
					}
				}
			}

			if(node.isEmptyXmlTag()) {
				data.addHref(l);
			} else {
				obs.openAnchors.push(l);
				obs.openAnchorTexts.push(new StringBuilder());
			
			}
		}
	}

	private static class AppletTagExtractor implements TagExtractor {
		public void extract(HTMLMetaData data, TagNode node, ExtractingParseObserver obs) {
			addBasicHrefs(data,node,"codebase","cdata");
		}
	}

	private static class AreaTagExtractor implements TagExtractor {
		public void extract(HTMLMetaData data, TagNode node, ExtractingParseObserver obs) {
			String url = node.getAttribute("href");
			if(url != null) {
				url = decodeCharEnt(url);
				ArrayList<String> l = new ArrayList<String>();
				l.add(PATH);
				l.add(makePath("AREA","href"));
				l.add("url");
				l.add(url);
				for(String a : new String[] {"rel"}) {
					String v = node.getAttribute(a);
					if(v != null) {
						l.add(a);
						l.add(v);
					}
				}
				data.addHref(l);
			}
		}
	}

	private static class BaseTagExtractor implements TagExtractor {
		public void extract(HTMLMetaData data, TagNode node, ExtractingParseObserver obs) {
			String url = node.getAttribute("href");
			if(url != null) {
				url = decodeCharEnt(url);
				data.setBaseHref(url);
			}
		}
	}
	
	private static class ButtonTagExtractor implements TagExtractor {
		public void extract(HTMLMetaData data, TagNode node, ExtractingParseObserver obs) {
			addBasicHrefs(data,node,"formaction");
		}
	}

	private static class DivTagExtractor implements TagExtractor {
		public void extract(HTMLMetaData data, TagNode node, ExtractingParseObserver obs) {
			addHrefsOnclick(data,node);
		}
	}

	private static class EmbedTagExtractor implements TagExtractor {
		public void extract(HTMLMetaData data, TagNode node, ExtractingParseObserver obs) {
			addBasicHrefs(data,node,"src");
		}
	}
	
	private static class EmbedVideoTagExtractor implements TagExtractor {
		public void extract(HTMLMetaData data, TagNode node, ExtractingParseObserver obs) {
			addBasicHrefs(data,node,"src","poster");
		}
	}

	private static class FormTagExtractor implements TagExtractor {
		public void extract(HTMLMetaData data, TagNode node, ExtractingParseObserver obs) {
			ArrayList<String> l = new ArrayList<String>();
			String url = node.getAttribute("action");
			if(url != null) {
				url = decodeCharEnt(url);
				// got data:
				l.add(PATH);
				l.add(makePath("FORM","action"));
				l.add("url");
				l.add(url);
				for(String a : new String[] {"target","method"}) {
					String v = node.getAttribute(a);
					if(v != null) {
						l.add(a);
						l.add(v);
					}
				}
				data.addHref(l);
			}
		}
	}

	private static class FrameTagExtractor implements TagExtractor {
		public void extract(HTMLMetaData data, TagNode node, ExtractingParseObserver obs) {
			addBasicHrefs(data,node,"src");
		}
	}

	private static class IFrameTagExtractor implements TagExtractor {
		public void extract(HTMLMetaData data, TagNode node, ExtractingParseObserver obs) {
			addBasicHrefs(data,node,"src");
		}
	}

	private static class ImgTagExtractor implements TagExtractor {
		public void extract(HTMLMetaData data, TagNode node, ExtractingParseObserver obs) {
			addHrefWithAttrs(data,node,"src","alt","title");
			addBasicHrefs(data,node,"longdesc");
		}
	}

	private static class InputTagExtractor implements TagExtractor {
		public void extract(HTMLMetaData data, TagNode node, ExtractingParseObserver obs) {
			addBasicHrefs(data,node,"src","formaction");
			addHrefsOnclick(data,node);
		}
	}

	private static class LinkTagExtractor implements TagExtractor {
		public void extract(HTMLMetaData data, TagNode node, ExtractingParseObserver obs) {
			ArrayList<String> l = getAttrListUrl(node,"href","rel","type");
			if(l != null) {
				data.addLink(l);
			}
		}
	}

	private static class MenuitemTagExtractor implements TagExtractor {
		public void extract(HTMLMetaData data, TagNode node, ExtractingParseObserver obs) {
			addBasicHrefs(data,node,"icon");
		}
	}

	private static class MetaTagExtractor implements TagExtractor {
		public void extract(HTMLMetaData data, TagNode node, ExtractingParseObserver obs) {
			ArrayList<String> l = getAttrList(node,"name","rel","content","http-equiv","property");
			if(l != null) {
				data.addMeta(l);
			}
		}
	}

	private static class ObjectTagExtractor implements TagExtractor {
		public void extract(HTMLMetaData data, TagNode node, ExtractingParseObserver obs) {
			addBasicHrefs(data,node,"codebase","cdata","data");
		}
	}

	private static class QuotationLinkTagExtractor implements TagExtractor {
		public void extract(HTMLMetaData data, TagNode node, ExtractingParseObserver obs) {
			addBasicHrefs(data,node,"cite");
		}
	}

	private static class ScriptTagExtractor implements TagExtractor {
		public void extract(HTMLMetaData data, TagNode node, ExtractingParseObserver obs) {
			ArrayList<String> l = getAttrListUrl(node,"src","type");
			if(l != null) {
				data.addScript(l);
			}
		}
	}

	private void patternCSSExtract(HTMLMetaData data, Pattern pattern, String content) {
		Matcher m = pattern.matcher(content);
		int idx = 0;
		int contentLen = content.length();
		if (contentLen > 100000)
			// extract URLs only from the first 100 kB
			contentLen = 100000;
		while((idx < contentLen) && m.find()) {
			idx = m.end();
			String url = m.group(1);
			url = cssUrlTrimPattern.matcher(url).replaceAll("");
			if (!url.isEmpty()) {
				data.addHref("path","STYLE/#text","href", url);
			}
		}
	}

	private static String patternJSExtract(Pattern pattern, String content) {
		Matcher m = pattern.matcher(content);
		if (m.find()) {
			return m.group(2);
		}
		return null;
	}

	public static String decodeCharEnt(String text) {
		try {
			return org.apache.commons.text.StringEscapeUtils.unescapeHtml4(text);
		} catch (Throwable e) {
			return text;
		}
	}
}
