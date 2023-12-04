package com.eulerity.hackathon.imagefinder;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/* Fetch HTML (JSoup) */
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/* Data structures */
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import java.lang.reflect.Type;
import java.util.*;

/* URI parsing */
import java.net.URI;
import java.net.URISyntaxException;

/* Atomics thread safety */
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/* Http */
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;



@WebServlet(
    name = "ImageFinder",
    urlPatterns = {"/main"}
)
public class ImageFinder extends HttpServlet{
	private static final long serialVersionUID = 1L;

	protected static final Gson GSON = new GsonBuilder().create();

	//This is just a test array
	public static final String[] testImages = {
			"https://images.pexels.com/photos/545063/pexels-photo-545063.jpeg?auto=compress&format=tiny",
			"https://images.pexels.com/photos/464664/pexels-photo-464664.jpeg?auto=compress&format=tiny",
			"https://images.pexels.com/photos/406014/pexels-photo-406014.jpeg?auto=compress&format=tiny",
			"https://images.pexels.com/photos/1108099/pexels-photo-1108099.jpeg?auto=compress&format=tiny"
  	};

	/* Maximum recursion depth (Increase at your own risk, will be slow due to ML classifer compute) */
	private static final int MAX_LEVELS = 1;

	/* Maximum number of pages to crawl (Increase at your own risk, will be slow due to ML classifier compute) */
	private static final int MAX_PAGES = 1;

	/* Number of threads */
	private static final int NUM_THREADS = 10;

	/* Current number of pages crawled, used to stop crawling when MAX_PAGES is reached. Atomic used for thread saftety and preventing data races */
	private AtomicInteger pageCount = new AtomicInteger(0);

	@Override
	protected final void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		resp.setContentType("text/json");
		String path = req.getServletPath();
		String url = req.getParameter("url");
		System.out.println("Got request of:" + path + " with query param:" + url);
		//resp.getWriter().print(GSON.toJson(testImages));

		List<String> imageUrls = startCrawlingFromUrl(url);
		//resp.getWriter().print(GSON.toJson(imageUrls));
		Map<String, List<String>> aggregatedClassifications = new HashMap<>();
		for (String imageUrl : imageUrls) {
			Map<String, List<String>> classifications = classifyImage(imageUrl);
			aggregatedClassifications.putAll(classifications);
		}

    	resp.getWriter().print(GSON.toJson(aggregatedClassifications)); 

	}

	/**
	 * Classifies an image using the classifier service/API
	 * @param imageUrl Image url to classify
	 * @return Map of image url to list of labels
	 */
	private Map<String, List<String>> classifyImage(String imageUrl) {
		String classifierEndpoint = "http://localhost:8000/process";
		Map<String, List<String>> result = new HashMap<>();
	
		// Check if the imageUrl ends with '.svg'
		if (imageUrl.toLowerCase().endsWith(".svg")) {
			result.put(imageUrl, Arrays.asList("logo"));
			return result;
		}
	
		try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
			HttpPost request = new HttpPost(classifierEndpoint);
			// JSON payload
			Map<String, String> data = new HashMap<>();
			data.put("image_url", imageUrl);
			String jsonPayload = GSON.toJson(data);
			StringEntity entity = new StringEntity(jsonPayload);
			request.setEntity(entity);
			request.setHeader("Accept", "application/json");
			request.setHeader("Content-type", "application/json");
	
			// Send request
			HttpResponse response = httpClient.execute(request);
			if (response.getStatusLine().getStatusCode() == 200) {
				String jsonResponse = EntityUtils.toString(response.getEntity());
				// Parsing the JSON response
				Type responseType = new TypeToken<Map<String, Map<String, Integer>>>(){}.getType();
				Map<String, Map<String, Integer>> classifications = GSON.fromJson(jsonResponse, responseType);
				List<String> labels = new ArrayList<>(classifications.get("classifications").keySet());
				result.put(imageUrl, labels);
			} 
		} catch (Exception e) {
			System.out.println("Could not classify: " + imageUrl);
		}
		return result;
	}
	
	/**
	 * Gets the domain name from a given url
	 * @param url Source url
	 * @return Domain name of url
	 */
	private String getDomainName(String url) throws URISyntaxException {
		URI uri = new URI(url);
		String domain = uri.getHost();
		return domain.startsWith("www.") ? domain.substring(4) : domain;
	}

	/**
	 * Starts crawling from a given url
	 * @param url Source url/page to crawl
	 * @return List of image URLs found on source page
	 */
	private List<String> startCrawlingFromUrl(String url) {
		pageCount.set(0); // Reset the page count for each new request
		List<String> imageUrls = new ArrayList<>();
		// We pass a hash set to our crawler to avoid visiting duplicates (i.e. crawling/visiting the same url twice)
		HashSet<String> visitedUrls = new HashSet<>();
		ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
		String baseDomain;
		try {
			baseDomain = getDomainName(url);
		} catch (URISyntaxException e) {
			System.out.println("Error parsing url: " + url + " with error: " + e.getMessage());
			e.printStackTrace();
			return imageUrls;
		}
		// Grab images subroutine and sub-page handling, starting at depth of 0
		executor.submit(() -> extractImagesAndCrawlSubpages(url, baseDomain, visitedUrls, imageUrls, 0));
		executor.shutdown();
		while (!executor.isTerminated()) {
			// Wait for all threads to finish
		}
		return imageUrls;
	}

	/**
	 * Extracts images from a given url and crawls sub-pages
	 * @param url Source url/page to crawl
	 * @param visitedUrls Set of urls that have already been visited
	 * @param imageUrls List of image URLs found on source page
	 * @param currentLevel Current depth of recursion
	 */
	private void extractImagesAndCrawlSubpages(String url, String baseDomain, HashSet<String> visitedUrls, List<String> imageUrls, int currentLevel) {
		// Check if we've reached our max number of pages
		if (pageCount.get() >= MAX_PAGES) {
			return;
		}

		// Check if url is valid and not already visited
		if (url == null || url.isEmpty() || visitedUrls.contains(url) || currentLevel >= MAX_LEVELS) { 
			return;
		}

		// Check if url is within the base domain
		try {
			if (!getDomainName(url).equals(baseDomain)) {
				return;
			}
		} catch (URISyntaxException e) {
			System.out.println("Error parsing url: " + url + " with error: " + e.getMessage());
			e.printStackTrace();
			return;
		}

		try {
			Document doc = Jsoup.connect(url).get();
			Elements images = doc.select("img");
			// Add image urls to list
			for (Element image : images) {
				String imageUrl = image.absUrl("src");
				if (imageUrl != null && !imageUrl.isEmpty()) {
					imageUrls.add(imageUrl);
				}
			}
			// Add current url to visited set
			visitedUrls.add(url);

			// Increment page count
			pageCount.incrementAndGet();

			// Recursively crawl sub-pages, while checking for depth and pages limits
			if (currentLevel < MAX_LEVELS - 1 && pageCount.get() < MAX_PAGES) {
				ExecutorService pageExecutor = Executors.newFixedThreadPool(NUM_THREADS);
				Elements links = doc.select("a[href]");
				for (Element link : links) {
					String subPageUrl = link.absUrl("href");
					if (!visitedUrls.contains(subPageUrl)) {
						pageExecutor.submit(() -> extractImagesAndCrawlSubpages(subPageUrl, baseDomain, visitedUrls, imageUrls, currentLevel + 1));
					}
				}
				pageExecutor.shutdown();
				while (!pageExecutor.isTerminated()) {
					// Wait for all threads to finish
				}
			}
			
		} catch (IOException e) {
			System.out.println("Error crawling url: " + url + " with error: " + e.getMessage());
			e.printStackTrace();
		}
	}
}