package org.archive.resource.warc.record;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

import com.google.common.io.CharStreams;
import org.archive.resource.MetaData;
import org.archive.resource.Resource;
import org.archive.resource.ResourceConstants;
import org.archive.resource.ResourceContainer;
import org.archive.resource.ResourceFactory;
import org.archive.resource.ResourceParseException;
import org.json.JSONException;
import org.json.JSONTokener;

public class WARCJSONMetaDataResourceFactory implements ResourceFactory, ResourceConstants {
	private static final Charset UTF8 = Charset.forName("UTF-8");

	public WARCJSONMetaDataResourceFactory() {
	}

	public Resource getResource(InputStream is, MetaData parentMetaData,
			ResourceContainer container) throws ResourceParseException,
			IOException {


		MetaData md;
		try {
			String input = CharStreams.toString(new InputStreamReader(is, UTF8));
			md = new MetaData(new JSONTokener(input));
		} catch (JSONException e) {
			throw new ResourceParseException(e);
		}
		return new WARCJSONMetaDataResource(md, container);
	}

}
