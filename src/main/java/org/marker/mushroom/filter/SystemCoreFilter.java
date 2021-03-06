package org.marker.mushroom.filter;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;

import org.marker.mushroom.alias.CacheO;
import org.marker.mushroom.alias.LOG;
import org.marker.mushroom.core.AppStatic;
import org.marker.mushroom.core.config.impl.SystemConfig;
import org.marker.mushroom.core.proxy.SingletonProxyFrontURLRewrite;
import org.marker.mushroom.holder.SpringContextHolder;
import org.marker.mushroom.holder.WebRealPathHolder;
import org.marker.mushroom.utils.DateUtils;
import org.marker.mushroom.utils.FileTools;
import org.marker.mushroom.utils.HttpUtils;
import org.marker.urlrewrite.URLRewriteEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * 
 * 系统前端核心过滤器
 * 1. 反向代理真实IP获取，主要是请求头中的X-Real-IP参数
 * 2. URL重写功能 (主要是重写前端访问地址)
 * 
 * 
 * @author marker
 * 
 * 
 * */
public class SystemCoreFilter implements Filter {
	
	/** 日志记录器 */ 
	protected Logger logger =  LoggerFactory.getLogger(LOG.WEBFOREGROUND); 

	// URL重写引擎
	private static URLRewriteEngine rewrite = SingletonProxyFrontURLRewrite.getInstance();
 
	// 排除过滤格式
	private Pattern suffixPattern; 
	
	/** 请求字符编码 */
	private static final String ENCODING = "utf-8";
	
	/** 响应内容类型 */
	private static final String CONTENT_TYPE = "text/html;charset=utf-8";


	/** 系统配置信息 */
	private static final SystemConfig syscfg = SystemConfig.getInstance();
	
	@Override
	public void doFilter(ServletRequest req, ServletResponse res,
			FilterChain chain) throws IOException, ServletException {
		// 记录开始执行时间
		req.setAttribute(AppStatic.WEB_APP_STARTTIME, System.currentTimeMillis());
		
		req.setCharacterEncoding(ENCODING);// 设置请求字符编码
		res.setContentType(CONTENT_TYPE);// 设置响应字符编码
		
		HttpServletRequest  request  = (HttpServletRequest) req;
		HttpServletResponse response = (HttpServletResponse) res;
		
		String uri  = request.getRequestURI();
		
		
		
		
		/* 
		 * ============================================
		 *              系统内置路径过滤
		 * ============================================
		 */
		if(uri.startsWith("/public")){
			chain.doFilter(req, response);
			return; // 因为这里是公共文件，所以直接返回了
		}
		if(uri.startsWith("/themes")){
			chain.doFilter(req, response);
			return; // 因为这里是公共文件，所以直接返回了
		}
		if(uri.startsWith("/modules")){
			chain.doFilter(req, response);
			return; // 因为这里是model核心路径，所以直接返回了
		}
		// 插件路径，进入插件过滤器  // uri: /plugin/dsdsd.html
		uri = uri.replace(request.getContextPath(), "");// 移除项目名称 
		if(uri.startsWith("/plugin/")){
			chain.doFilter(request, response);
			return;
		}
		if(uri.startsWith("/SecurityCode")){ // 验证码接口
			chain.doFilter(req, response);
			return;
		}
		if(uri.startsWith("/fetch")){ // 统计接口
			chain.doFilter(req, response);
			return;
		}
		
		
		/* ============================================
		 *              排除过滤格式
		 * ============================================
		 */
		int dotIndex= uri.lastIndexOf(".") + 1;// 请求文件后缀
		if (dotIndex != -1) {// 有后缀
			String suffix = uri.substring(dotIndex, uri.length());
			if (suffixPattern.matcher(suffix).matches()) {
				chain.doFilter(req, response);
				return; // 因为这里是静态文件，所以直接返回了
			}
		}

		
		// 页面静态读取
		if(syscfg.isStaticPage()){
			System.out.println(""+uri); 
			CacheManager cm =  SpringContextHolder.getBean("cacheManager");
			 
			Cache cache = cm.getCache(CacheO.STATIC_HTML);
			 
			String lang = HttpUtils.getLanguage(request);
			
			 
			request.setAttribute("rewriterUrl", uri);
			
			Element element = cache.get(lang+"_"+uri);
			if(element != null){
				System.out.println("get cache....");
				String htmlFilePath = element.getObjectValue().toString();
				
				String filePath = WebRealPathHolder.REAL_PATH + htmlFilePath;
				 
				String content = FileTools.getFileContet(new File(filePath), FileTools.FILE_CHARACTER_UTF8);
				PrintWriter pw = response.getWriter();
				pw.write(content);
				pw.flush();
				pw.close();
				return;
			}
		}
		
		
		
		
		/* 
		 * ============================================
		 *               cookies追中
		 * ============================================
		 */
		if(!HttpUtils.checkCookie(request, "FETCHSESSIONID")){
			Cookie cookie = new Cookie("FETCHSESSIONID",UUID.randomUUID().toString().replaceAll("-","").toUpperCase());
			int life = DateUtils.getCurentDayRemainingTime();
			cookie.setMaxAge(life);// 当天内有效
			response.addCookie(cookie);
		}
		
	
		
		/* 
		 * ============================================
		 *                URI -> URL 解码操作
		 * ============================================
		 */
		String url = rewrite.decoder(uri); 
		logger.info("URL: {} => {}", uri, url);

		
		String ip = HttpUtils.getRemoteHost(request);// IP地址获取
		req.setAttribute(AppStatic.REAL_IP, ip);// 将用户真实IP写入请求属性
		req.setAttribute(AppStatic.WEB_APP_URL, HttpUtils.getRequestURL(request));// 网址路径
		req.setAttribute(AppStatic.WEB_APP_THEME_URL, HttpUtils.getRequestURL(request)+"/themes/"+syscfg.get(SystemConfig.THEME_PATH)+"/");// 网址路径
		
		req.getRequestDispatcher(url).forward(request, response);// 请求转发
	}



	@Override
	public void init(FilterConfig config) throws ServletException {
		String excludeFormat = config.getInitParameter("exclude_format");
		this.suffixPattern = Pattern.compile("("+excludeFormat+")");
		logger.info("mrcms system filter initing...");
	}

	
	@Override
	public void destroy() {
		logger.info("mrcms system filter destroying..."); 
	}
}
