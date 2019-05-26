package com.msgc.controller;

import com.msgc.config.LoggedUserSessionContext;
import com.msgc.constant.SessionKey;
import com.msgc.constant.response.ResponseWrapper;
import com.msgc.entity.User;
import com.msgc.entity.UserCookie;
import com.msgc.exception.ResourceNotFoundException;
import com.msgc.service.IUserCookieService;
import com.msgc.service.IUserService;
import com.msgc.utils.JsonUtil;
import com.msgc.utils.RandomHeadImageUtil;
import com.msgc.utils.RegexCheckUtils;
import com.msgc.utils.WebUtil;
import com.msgc.utils.csv.CsvUtilAdapter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
*Type: UserController
* Description: 用户模块
* @author LMM
 */
@Controller
@RequestMapping(value = "/user")
public class UserController {
	private final IUserService userService;
	private final IUserCookieService userCookieService;

	@Autowired
	public UserController(IUserCookieService userCookieService, IUserService userService) {
		this.userCookieService = userCookieService;
		this.userService = userService;
	}

	/**
	 * 登录
	 * @param userParam 接收传来的 user
     * @param model 设置提示信息
	 * @return 重定向至首页
	 */
    @PostMapping(value = "/login.action")
    public String login(@ModelAttribute User userParam, Model model) {
        User user = userService.login(userParam);
        if(user == null) {
            // 登录失败，返回登录界面
            model.addAttribute("msg", "账号或密码错误");
            return "login";
        } else {
            // 登录成功
			//勾选了自动登录
			if("on".equalsIgnoreCase(WebUtil.getRequest().getParameter("autoLogin"))){
				try{
					UserCookie mapping = new UserCookie();
					mapping.setCookie(UUID.randomUUID().toString().replace("-", ""));
					mapping.setUserId(user.getId());
					// 保存至数据库
					long twoWeeks_milli = 1000 * 60 * 60 * 24 * 14;
					mapping.setExpirationDate(new Date(System.currentTimeMillis() + twoWeeks_milli));
					userCookieService.save(mapping);
					// 将凭据保存至浏览器 cookie
					int twoWeeks_second = 60 * 60 * 24 * 14;
					WebUtil.setCookie(SessionKey.AUTO_LOGIN, mapping.getCookie(), twoWeeks_second);
				}catch (Exception e){
					e.printStackTrace();
					//写数据库 cookieUserMapping 失败
				}
			}
			HttpSession session = WebUtil.setSessionUser(user);
            if(session.getAttribute(SessionKey.REDIRECT_URL) != null){
				String redirect = "redirect:" + String.valueOf(session.getAttribute(SessionKey.REDIRECT_URL));
				session.removeAttribute(SessionKey.REDIRECT_URL);
            	return redirect;
			}
            return "redirect:/index";
        }
    }

	/**
	 * 用户注册
	 * @param user	接收的用户类
	 * @param model	视图返回字段
	 * @return 原本请求的 url 或 index
	 */
	@PostMapping(value = "/register.action")
	public String register(@ModelAttribute User user, Model model) {
		User registerUser = userService.register(user);
		if(registerUser == null) {
			// 注册失败
			model.addAttribute("msg", "账号已存在");
			return "/register";
		} else {
			// 注册成功
			HttpSession session = WebUtil.getRequest().getSession();
			session.setAttribute(SessionKey.USER, user);
			LoggedUserSessionContext.putIfAbsent(user.getId(), session);
			if(session.getAttribute(SessionKey.REDIRECT_URL) != null){
				String redirect = "redirect:" + String.valueOf(session.getAttribute(SessionKey.REDIRECT_URL));
				session.removeAttribute(SessionKey.REDIRECT_URL);
				return redirect;
			}
			return "redirect:/index";
		}
	}


	// 退出登录，清空 session 中的 user, 清除cookie
	@GetMapping(value = "/logout.action")
	public String logout(HttpSession session) {
		WebUtil.expireCookie(SessionKey.AUTO_LOGIN);
		User user = (User)session.getAttribute(SessionKey.USER);
        LoggedUserSessionContext.remove(user.getId());
		session.invalidate();
		return "redirect:/login";
	}

    //到个人中心页profile
    @GetMapping(value = "/profile")
    public String profilePage() {
        return "/user/profile";
    }

    // 到修改密码页
    @GetMapping(value = "/changepasswd")
    public String changePasswdPage() {
        return "/user/modifyPassword";
    }

	// 修改密码提交
	@ResponseBody
	@PostMapping(value = "/changepasswd.action")
    public String changePasswd() {
		HttpServletRequest request = WebUtil.getRequest();
		User user = (User)request.getSession().getAttribute(SessionKey.USER);
		if(user != null){
			String oldPassword = request.getParameter("oldPassword");
			String newPassword = request.getParameter("newPassword");
			if(user.getPassword().equals(oldPassword) && RegexCheckUtils.checkUserPassword(newPassword)){
				user.setPassword(newPassword);
				user = userService.save(user);
				request.getSession().setAttribute(SessionKey.USER, user);
				return JsonUtil.toJson(ResponseWrapper.success("ok"));
			}
            return JsonUtil.toJson(ResponseWrapper.fail("密码格式错误或不正确"));
		}
        return JsonUtil.toJson(ResponseWrapper.fail("未登录"));
    }

    // 修改个人信息
	@PostMapping(value = "/updateProfile.action")
    public String updateProfile(User userParam) {
		HttpSession session = WebUtil.getRequest().getSession();
		User user = (User)session.getAttribute(SessionKey.USER);
		if(user != null){
			if(StringUtils.isNotBlank(userParam.getHeadImage())){
				user.setHeadImage(userParam.getHeadImage());
			}
			if(StringUtils.isNotBlank(userParam.getNickname())){
				user.setNickname(userParam.getNickname());
			}
			//user.setRealname(userParam.getRealname());
			//user.setSex(userParam.getSex());
			//user.setTel(userParam.getTel());
			//user.setIdcard(userParam.getIdcard());
			user.setQq(userParam.getQq());
			user.setWechat(userParam.getWechat());
			user.setEmail(userParam.getEmail());
			user = userService.save(user);
			session.setAttribute(SessionKey.USER, user);
			return "/user/profile";
		}
		return "login";
    }

    // 批量导入，只测试用
	@ResponseBody
	@GetMapping(value = "/import")
    public String importUsers(){
		HttpSession session = WebUtil.getRequest().getSession();
		User user = (User)session.getAttribute(SessionKey.USER);
		if(!user.getId().equals(1)){
			throw new ResourceNotFoundException();
		}
		try {
			List<User> userList = CsvUtilAdapter.read("C:\\Users\\Administrator\\Desktop\\importUser.csv");
			userService.save(userList);
		} catch (IOException e) {
			e.printStackTrace();
		}
		return "ok";
	}

	// 更新所有用户的头像 仅测试使用
	@ResponseBody
	@GetMapping(value = "/updateHeadImage")
    public String updateHeadImage(){
		HttpSession session = WebUtil.getRequest().getSession();
		User user = (User)session.getAttribute(SessionKey.USER);
		if(!user.getId().equals(1)){
			throw new ResourceNotFoundException();
		}
		List<User> userList = userService.findAll();
		userList.forEach(u -> u.setHeadImage(RandomHeadImageUtil.next()));
		userService.save(userList);
		return "ok";
	}

}
