#!groovy
def call() {


        def cfg = pipelineCfg()
        echo "获取配置完成"
        echo cfg
        switch(cfg.type) {
            case "python": 
                pythonPipeline(cfg)
                break
            case "nodejs":
                nodejsPipeline(cfg)
                break
            case "maven":
                echo "执行maven编译"
                mavenPipeline(cfg)
                break
        }

}
