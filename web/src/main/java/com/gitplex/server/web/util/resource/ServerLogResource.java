package com.gitplex.server.web.util.resource;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import org.apache.shiro.authz.UnauthorizedException;
import org.apache.tika.mime.MimeTypes;
import org.apache.wicket.request.resource.AbstractResource;

import com.gitplex.launcher.bootstrap.Bootstrap;
import com.gitplex.server.security.SecurityUtils;
import com.gitplex.server.util.FileUtils;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;

public class ServerLogResource extends AbstractResource {

	private static final long serialVersionUID = 1L;

	@Override
	protected ResourceResponse newResourceResponse(Attributes attributes) {
		if (!SecurityUtils.canManageSystem()) 
			throw new UnauthorizedException();

		ResourceResponse response = new ResourceResponse();
		response.setContentType(MimeTypes.OCTET_STREAM);
		
		response.disableCaching();
		
		try {
			response.setFileName(URLEncoder.encode("server.log", Charsets.UTF_8.name()));
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
		response.setWriteCallback(new WriteCallback() {

			@Override
			public void writeData(Attributes attributes) throws IOException {
				String content = Joiner.on("\n").join(readServerLog());
				attributes.getResponse().getOutputStream().write(content.getBytes(Charsets.UTF_8));
			}				
		});

		return response;
	}

	public static List<String> readServerLog() {
		File logFile = new File(Bootstrap.installDir, "logs/server.log");
    	List<String> lines = new ArrayList<>();
    	int index = logFile.getParentFile().list().length;
    	try {
			File logDir = logFile.getParentFile();
			for (int i=index; i>=1; i--) {
				File rollFile = new File(logDir, logFile.getName() + "." + i);
				if (rollFile.exists())
					lines.addAll((FileUtils.readLines(rollFile, Charsets.UTF_8)));
			}
			lines.addAll((FileUtils.readLines(logFile, Charsets.UTF_8)));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
    	return lines;
	}
	
}