FROM docker.elastic.co/elasticsearch/elasticsearch:8.17.0

# 安装 IK 中文分词器
RUN bin/elasticsearch-plugin install --batch https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-8.17.0.zip
