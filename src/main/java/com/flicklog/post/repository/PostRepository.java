package com.flicklog.post.repository;

import com.flicklog.post.model.Post;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;

public interface PostRepository extends MongoRepository<Post, String> {

    // Mirrors PostMessage.find().sort({_id: -1}).limit(LIMIT).skip(startIndex)
    List<Post> findAllByOrderByIdDesc(Pageable pageable);

    // Mirrors the $or: [{title: regex}, {tags: {$in: tagsArray}}] search
    @Query("{ '$or': [ { 'title': { '$regex': ?0, '$options': 'i' } }, { 'tags': { '$in': ?1 } } ] }")
    List<Post> searchByTitleOrTags(String titleRegex, List<String> tags);
}
