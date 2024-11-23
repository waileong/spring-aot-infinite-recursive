package org.example.springaotinfiniterecursive;

import java.util.Map;

public class TestBean {
    private Map<String, TestBean> testBeans;

    public Map<String, TestBean> getTestBeans() {
        return testBeans;
    }

    public void setTestBeans(Map<String, TestBean> testBeans) {
        this.testBeans = testBeans;
    }
}
