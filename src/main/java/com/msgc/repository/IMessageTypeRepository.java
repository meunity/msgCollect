package com.msgc.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.msgc.entity.MessageType;
/**
* Description: DAO. 
*   Deal directly with the database, 
*   if you customize the query, take the index first
* 
* 		Please follow the prefix convention below
*
*   getOne ------------ getXXX
*   getMultiple ------- listXXX
*   count ------------- countXXX
*   getOne ------------ getXXX
*   insert ------------ saveXXX
*   delete ------------ deleteXXX
*   modify ------------ updateXXX
*
*/
public interface IMessageTypeRepository extends JpaRepository<MessageType, Integer> {


}
