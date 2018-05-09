package com.mmall.common;


/**
 * Created by li
 */
public class Const {

    public static final String CURRENT_USER = "currentUser";

    public static final String EMAIL = "email";
    public static final String USERNAME = "username";

    //没有枚举那么重，但是又起到分组的作用，还是常量
    public interface Role{
        int ROLE_CUSTOMER = 0;//普通用户
        int ROLE_ADMIN = 1;//管理员
    }
}
