import redis.clients.jedis.Jedis;
import redis.clients.jedis.ZParams;

import java.util.*;

public class Chapter01 {
    private static final int ONE_WEEK_IN_SECONDS = 7 * 86400;
    private static final int VOTE_SCORE = 432;
    private static final int ARTICLES_PER_PAGE = 25;

    public static void main(String[] args) {
        new Chapter01().run();
    }

    private void run() {
        Jedis conn = new Jedis("192.168.74.139", 6379);
        conn.select(15);

        String articleId = postArticle(
                conn, "username", "A title", "http://www.google.com");
        System.out.println("We posted a new article with id: " + articleId);
        System.out.println("Its HASH looks like:");
        Map<String, String> articleData = conn.hgetAll("article:" + articleId);
        for (Map.Entry<String, String> entry : articleData.entrySet()) {
            System.out.println("  " + entry.getKey() + ": " + entry.getValue());
        }

        System.out.println();

        articleVote(conn, "other_user", "article:" + articleId);
        String votes = conn.hget("article:" + articleId, "votes");
        System.out.println("We voted for the article, it now has votes: " + votes);
        assert Integer.parseInt(votes) > 1;

        System.out.println("The currently highest-scoring articles are:");
        List<Map<String, String>> articles = getArticles(conn, 1);
        printArticles(articles);
        assert articles.size() >= 1;

        addGroups(conn, articleId, new String[]{"new-group"});
        System.out.println("We added the article to a new group, other articles include:");
        articles = getGroupArticles(conn, "new-group", 1);
        printArticles(articles);
        assert articles.size() >= 1;
    }


    private String postArticle(Jedis conn, String user, String title, String link) {
        //生成一个文章的id
        String articleId = String.valueOf(conn.incr("article:"));
        String voted = "voted:" + articleId;
        //将用户添加到文章的已经投票的用户名单
        conn.sadd(voted, user);
        //设置名单的过期时间
        conn.expire(voted, ONE_WEEK_IN_SECONDS);

        long now = System.currentTimeMillis() / 1000;
        String article = "article:" + articleId;
        HashMap<String, String> articleData = new HashMap<String, String>();
        articleData.put("title", title);
        articleData.put("link", link);
        articleData.put("user", user);
        articleData.put("now", String.valueOf(now));
        articleData.put("votes", "1");
        //发布文章
        conn.hmset(article, articleData);
        //发布文章的分数
        conn.zadd("score:", now + VOTE_SCORE, article);
        //发布文章的时间
        conn.zadd("time:", now, article);

        return articleId;
    }

    private void articleVote(Jedis conn, String user, String article) {
        long cutoff = (System.currentTimeMillis() / 1000) - ONE_WEEK_IN_SECONDS;
        //获取文章发布时间(分数),一周之前的直接返回不能投票
        if (conn.zscore("time:", article) < cutoff) {
            return;
        }
        //取出文章id
        String articleId = article.substring(article.indexOf(':') + 1);
        //将用户加入投票者的集合
        if (conn.sadd("voted:" + articleId, user) == 1) {
            //增加文章分数
            conn.zincrby("score:", VOTE_SCORE, article);
            //文章投票数量加1
            conn.hincrBy(article, "votes", 1);
        }
    }


    private List<Map<String, String>> getArticles(Jedis conn, int page) {
        return getArticles(conn, page, "score:");
    }

    private List<Map<String, String>> getArticles(Jedis conn, int page, String order) {
        int start = (page - 1) * ARTICLES_PER_PAGE;
        int end = start + ARTICLES_PER_PAGE - 1;
        //取出文章的多个ID
        Set<String> ids = conn.zrevrange(order, start, end);
        List<Map<String, String>> articles = new ArrayList<Map<String, String>>();
        for (String id : ids) {
            //获取该文章所有信息
            Map<String, String> articleData = conn.hgetAll(id);
            articleData.put("id", id);
            articles.add(articleData);
        }

        return articles;
    }

    private void addGroups(Jedis conn, String articleId, String[] toAdd) {
        String article = "article:" + articleId;
        for (String group : toAdd) {
            conn.sadd("group:" + group, article);
        }
    }

    private List<Map<String, String>> getGroupArticles(Jedis conn, String group, int page) {
        return getGroupArticles(conn, group, page, "score:");
    }

    private List<Map<String, String>> getGroupArticles(Jedis conn, String group, int page, String order) {
        //为每个群组的每种排序顺序都创建一个键
        String key = order + group;
        //检查是否有已缓存的排序结果,如果没有就进行排序
        if (!conn.exists(key)) {
            //根据评分或者时间对群组的文章进行排序
            ZParams params = new ZParams().aggregate(ZParams.Aggregate.MAX);
            conn.zinterstore(key, params, "group:" + group, order);
            conn.expire(key, 60);
        }
        //分页获取文章
        return getArticles(conn, page, key);
    }

    private void printArticles(List<Map<String, String>> articles) {
        for (Map<String, String> article : articles) {
            System.out.println("  id: " + article.get("id"));
            for (Map.Entry<String, String> entry : article.entrySet()) {
                if (entry.getKey().equals("id")) {
                    continue;
                }
                System.out.println("    " + entry.getKey() + ": " + entry.getValue());
            }
        }
    }
}
