package org.archive.format.text.html;

import org.htmlparser.Node;
import org.htmlparser.Text;
import org.htmlparser.lexer.Lexer;
import org.htmlparser.util.ParserException;

import static org.archive.format.text.html.NodeUtils.SCRIPT_TAG_NAME;
import static org.archive.format.text.html.NodeUtils.STYLE_TAG_NAME;

public class CDATALexer extends Lexer {
	private static final long serialVersionUID = -8513653556979405106L;
	private Node cached;
	private boolean inJS;
	private boolean inCSS;

	private static enum STATE { DEFAULT, START_JS, START_CSS };
	private STATE state = STATE.DEFAULT;

	private int start = -1;
	private int end = -1;

	@Override
	public Node nextNode() throws ParserException {
		if (cached != null) {
			inJS = inCSS = false;
			Node tmp = cached;
			cached = null;
			return tmp;
		}
		Node got = null;
		switch (state) {
		case START_JS:
			got = super.parseCDATA(false);
			if (got != null) {
				inJS = true;
			}
			break;
		case START_CSS:
			got = super.parseCDATA(false);
			if (got != null) {
				inCSS = true;
			}
			break;
		default:
			break;
		}
		if (got != null) {
			Text t = (Text) got;
			start = t.getStartPosition();
			end = t.getEndPosition();
			while ((t = (Text) super.parseCDATA(false)) != null) {
				end = t.getEndPosition();
			}
			while ((got = super.nextNode()) != null) {
				if (inJS) {
					if (NodeUtils.isCloseTagNodeNamed(got, SCRIPT_TAG_NAME)) {
						cached = got;
						state = STATE.DEFAULT;
						return createStringNode(getPage(), start, end);
					} else {
						end = got.getEndPosition();
					}
				} else if (inCSS) {
					if (NodeUtils.isCloseTagNodeNamed(got, STYLE_TAG_NAME)) {
						cached = got;
						state = STATE.DEFAULT;
						return createStringNode(getPage(), start, end);
					} else {
						end = got.getEndPosition();
					}
				}
			}
			t = createStringNode(getPage(), start, end);
			state = STATE.DEFAULT;
			start = end = -1;
			return t;
		}
		got = super.nextNode();
		if (NodeUtils.isNonEmptyOpenTagNodeNamed(got, SCRIPT_TAG_NAME)) {
			state = STATE.START_JS;
		} else if (NodeUtils.isNonEmptyOpenTagNodeNamed(got, STYLE_TAG_NAME)) {
			state = STATE.START_CSS;
		} else if (NodeUtils.isCloseTagNodeNamed(got, SCRIPT_TAG_NAME)) {
			state = STATE.DEFAULT;
			inJS = false;
		} else if (NodeUtils.isCloseTagNodeNamed(got, STYLE_TAG_NAME)) {
			state = STATE.DEFAULT;
			inCSS = false;
		}
		return got;
	}

	public boolean inJS() {
		return inJS;
	}
	public boolean inCSS() {
		return inCSS;
	}
}
